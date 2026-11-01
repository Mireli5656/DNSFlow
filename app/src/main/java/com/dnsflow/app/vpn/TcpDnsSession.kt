package com.dnsflow.app.vpn

import com.dnsflow.app.dns.DNS_HEADER_SIZE
import java.security.SecureRandom

/** Hands one DNS query to the resolver. [reply] is called once, from any thread. */
fun interface DnsQueryHandler {
    fun handle(query: ByteArray, reply: (ByteArray) -> Unit)
}

/**
 * One TCP connection from an app to the virtual DNS server.
 *
 * This is a small TCP server for DNS only. The tunnel is a local, loss-free path, so segments
 * are not retransmitted; sending still respects the client's receive window.
 */
class TcpDnsSession(
    private val version: Int,
    private val clientAddress: ByteArray,
    private val serverAddress: ByteArray,
    private val clientPort: Int,
    private val serverPort: Int,
    private val sink: PacketSink,
    private val handler: DnsQueryHandler
) {

    private enum class State { SYN_RECEIVED, ESTABLISHED, LAST_ACK, CLOSED }

    private var state = State.SYN_RECEIVED
    private var sendUnacknowledged = 0
    private var sendNext = 0
    private var receiveNext = 0
    private var peerWindow = 0

    private var inbound = ByteArray(0)
    private var outbound = ByteArray(0)
    private var outboundOffset = 0
    private var outstandingQueries = 0
    private var finReceived = false
    private var lastActivityNanos = System.nanoTime()

    /** Answers the client's SYN. */
    @Synchronized
    fun start(syn: TcpSegment) {
        val initialSequence = random.nextInt()
        receiveNext = syn.sequence + 1
        peerWindow = syn.window
        sendUnacknowledged = initialSequence
        sendNext = initialSequence + 1
        sendSynAck()
    }

    @Synchronized
    fun onSegment(segment: TcpSegment) {
        if (state == State.CLOSED) return
        lastActivityNanos = System.nanoTime()

        if (segment.rst) {
            closeNow()
            return
        }
        if (segment.syn) {
            // The client repeated its SYN because it did not see our SYN-ACK.
            if (state == State.SYN_RECEIVED && segment.sequence + 1 == receiveNext) {
                sendSynAck()
            }
            return
        }
        if (!segment.ack) return

        if (state == State.SYN_RECEIVED) {
            if (segment.acknowledgment != sendNext) {
                sink.write(
                    PacketBuilder.tcp(
                        version, serverAddress, clientAddress, serverPort, clientPort,
                        segment.acknowledgment, 0, TcpSegment.FLAG_RST, 0
                    )
                )
                closeNow()
                return
            }
            sendUnacknowledged = sendNext
            state = State.ESTABLISHED
        }

        processAcknowledgment(segment)
        if (state == State.CLOSED) return
        processData(segment)
    }

    @Synchronized
    fun isClosed(): Boolean = state == State.CLOSED

    @Synchronized
    fun isIdle(nowNanos: Long, timeoutNanos: Long): Boolean =
        nowNanos - lastActivityNanos > timeoutNanos

    /** Resets the connection, for example when it has been idle for too long. */
    @Synchronized
    fun abort() {
        if (state == State.CLOSED) return
        send(sendNext, TcpSegment.FLAG_RST or TcpSegment.FLAG_ACK)
        closeNow()
    }

    private fun processAcknowledgment(segment: TcpSegment) {
        val acknowledged = segment.acknowledgment
        // Only acknowledgments between "unacknowledged" and "sent" are meaningful.
        if (acknowledged - sendUnacknowledged > 0 && acknowledged - sendNext <= 0) {
            sendUnacknowledged = acknowledged
        }
        peerWindow = segment.window

        if (state == State.LAST_ACK && sendUnacknowledged == sendNext) {
            closeNow()
            return
        }
        pump()
    }

    private fun processData(segment: TcpSegment) {
        // Bytes at the start of this segment that were already received.
        val overlap = receiveNext - segment.sequence
        if (overlap < 0) {
            // A gap. Ask the client to resend from the byte we expect.
            if (segment.payload.isNotEmpty() || segment.fin) sendAck()
            return
        }

        val fresh = when {
            overlap >= segment.payload.size -> ByteArray(0)
            overlap == 0 -> segment.payload
            else -> segment.payload.copyOfRange(overlap, segment.payload.size)
        }
        if (fresh.isNotEmpty()) {
            inbound += fresh
            receiveNext += fresh.size
        }
        if (segment.fin && !finReceived &&
            segment.sequence + segment.payload.size == receiveNext
        ) {
            finReceived = true
            receiveNext += 1
        }

        // Acknowledge data and FIN, including repeated ones. Pure ACKs get no reply.
        if (segment.payload.isNotEmpty() || segment.fin) sendAck()

        if (inbound.size > MAX_INBOUND_BYTES) {
            abort()
            return
        }
        extractQueries()
        maybeSendFin()
    }

    /** Takes complete length-prefixed DNS messages out of the received bytes. */
    private fun extractQueries() {
        while (state != State.CLOSED && inbound.size >= 2) {
            val length = ((inbound[0].toInt() and 0xFF) shl 8) or (inbound[1].toInt() and 0xFF)
            if (length < DNS_HEADER_SIZE) {
                abort()
                return
            }
            if (inbound.size < 2 + length) return
            if (outstandingQueries >= MAX_OUTSTANDING_QUERIES) {
                abort()
                return
            }

            val query = inbound.copyOfRange(2, 2 + length)
            inbound = inbound.copyOfRange(2 + length, inbound.size)
            outstandingQueries++
            handler.handle(query) { answer -> onAnswer(answer) }
        }
    }

    @Synchronized
    private fun onAnswer(answer: ByteArray) {
        if (state == State.CLOSED) return
        outstandingQueries--

        val framed = ByteArray(2 + answer.size)
        framed[0] = (answer.size ushr 8).toByte()
        framed[1] = answer.size.toByte()
        System.arraycopy(answer, 0, framed, 2, answer.size)

        outbound = outbound.copyOfRange(outboundOffset, outbound.size) + framed
        outboundOffset = 0

        pump()
        maybeSendFin()
    }

    /** Sends queued answer bytes while the client's receive window has room. */
    private fun pump() {
        while (state == State.ESTABLISHED && outboundOffset < outbound.size) {
            val inFlight = sendNext - sendUnacknowledged
            val room = peerWindow - inFlight
            if (room <= 0) return

            val size = minOf(outbound.size - outboundOffset, room, MSS)
            send(
                sendNext,
                TcpSegment.FLAG_ACK or TcpSegment.FLAG_PSH,
                outbound.copyOfRange(outboundOffset, outboundOffset + size)
            )
            sendNext += size
            outboundOffset += size
        }
        if (outboundOffset >= outbound.size) {
            outbound = ByteArray(0)
            outboundOffset = 0
        }
    }

    /** After the client has closed its side and every answer is sent, close ours. */
    private fun maybeSendFin() {
        if (state != State.ESTABLISHED || !finReceived) return
        if (outstandingQueries > 0 || outboundOffset < outbound.size) return

        send(sendNext, TcpSegment.FLAG_FIN or TcpSegment.FLAG_ACK)
        sendNext += 1
        state = State.LAST_ACK
    }

    private fun sendSynAck() {
        send(sendUnacknowledged, TcpSegment.FLAG_SYN or TcpSegment.FLAG_ACK, ByteArray(0), MSS)
    }

    private fun sendAck() {
        send(sendNext, TcpSegment.FLAG_ACK)
    }

    private fun send(sequence: Int, flags: Int, payload: ByteArray = ByteArray(0), mss: Int = 0) {
        sink.write(
            PacketBuilder.tcp(
                version, serverAddress, clientAddress, serverPort, clientPort,
                sequence, receiveNext, flags, RECEIVE_WINDOW, payload, mss
            )
        )
    }

    private fun closeNow() {
        state = State.CLOSED
        inbound = ByteArray(0)
        outbound = ByteArray(0)
        outboundOffset = 0
    }

    private companion object {
        const val MSS = 1400
        const val RECEIVE_WINDOW = 65535
        const val MAX_OUTSTANDING_QUERIES = 32
        const val MAX_INBOUND_BYTES = 70_000
        val random = SecureRandom()
    }
}
