package com.dnsflow.app.vpn

import com.dnsflow.app.dns.DnsMessage
import com.dnsflow.app.dns.DnsResolver
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val WORKER_THREADS = 12
private const val QUEUE_CAPACITY = 256
private const val MAX_TCP_SESSIONS = 256
private const val SWEEP_INTERVAL_SECONDS = 10L
private val SESSION_IDLE_NANOS = TimeUnit.SECONDS.toNanos(30)

/**
 * Takes IP packets from the tunnel and answers the DNS queries inside them.
 *
 * [handlePacket] is cheap and never blocks: the upstream lookup runs on worker threads, and the
 * finished answer is written back to the tunnel through [sink]. Queries and answers are never
 * logged or stored.
 */
class PacketForwarder(
    private val resolver: DnsResolver,
    private val sink: PacketSink
) : Closeable {

    /** Called after each upstream lookup with true on success, false on failure. */
    @Volatile
    var onUpstreamResult: ((Boolean) -> Unit)? = null

    private val executor = ThreadPoolExecutor(
        WORKER_THREADS,
        WORKER_THREADS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        NamedThreadFactory("dnsflow-worker")
    ).apply { allowCoreThreadTimeOut(true) }

    private val sweeper = ScheduledThreadPoolExecutor(1, NamedThreadFactory("dnsflow-sweeper"))
    private val sessions = ConcurrentHashMap<FlowKey, TcpDnsSession>()
    private val queryHandler = DnsQueryHandler { query, reply -> submit(query, reply) }

    @Volatile
    private var closed = false

    init {
        sweeper.scheduleWithFixedDelay(
            { sweep() },
            SWEEP_INTERVAL_SECONDS,
            SWEEP_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /** Handles one packet read from the tunnel. */
    fun handlePacket(buffer: ByteArray, length: Int) {
        if (closed) return
        val packet = IpPacket.parse(buffer, length) ?: return
        if (!VirtualDns.isServerAddress(packet.destination)) return

        when (packet.protocol) {
            IpPacket.PROTOCOL_UDP -> handleUdp(packet)
            IpPacket.PROTOCOL_TCP -> handleTcp(packet)
        }
    }

    override fun close() {
        closed = true
        sweeper.shutdownNow()
        executor.shutdownNow()
        sessions.clear()
    }

    private fun handleUdp(packet: IpPacket) {
        val datagram = UdpDatagram.parse(packet) ?: return
        if (datagram.destinationPort != DNS_PORT) return

        val query = datagram.payload
        if (!DnsMessage.isQuery(query)) return

        submit(query) { answer ->
            sink.write(
                PacketBuilder.udp(
                    packet.version,
                    packet.destination,
                    packet.source,
                    DNS_PORT,
                    datagram.sourcePort,
                    DnsMessage.fitForUdp(query, answer)
                )
            )
        }
    }

    private fun handleTcp(packet: IpPacket) {
        val segment = TcpSegment.parse(packet) ?: return
        val key = FlowKey(
            packet.version,
            ByteBuffer.wrap(packet.source),
            segment.sourcePort,
            segment.destinationPort
        )

        val existing = sessions[key]
        if (existing != null) {
            if (!existing.isClosed()) {
                existing.onSegment(segment)
                if (existing.isClosed()) sessions.remove(key, existing)
                return
            }
            sessions.remove(key, existing)
        }

        if (segment.rst) return

        // Anything except a new DNS connection is refused at once. This also makes Android's
        // "Private DNS" probe (TCP port 853) fail fast, so it falls back to plain DNS.
        val isNewDnsConnection =
            segment.destinationPort == DNS_PORT && segment.syn && !segment.ack
        if (!isNewDnsConnection || sessions.size >= MAX_TCP_SESSIONS) {
            sendReset(packet, segment)
            return
        }

        val session = TcpDnsSession(
            version = packet.version,
            clientAddress = packet.source,
            serverAddress = packet.destination,
            clientPort = segment.sourcePort,
            serverPort = segment.destinationPort,
            sink = sink,
            handler = queryHandler
        )
        sessions[key] = session
        session.start(segment)
    }

    /** Refuses a connection the way a closed port does (RFC 793). */
    private fun sendReset(packet: IpPacket, segment: TcpSegment) {
        val sequence: Int
        val acknowledgment: Int
        val flags: Int
        if (segment.ack) {
            sequence = segment.acknowledgment
            acknowledgment = 0
            flags = TcpSegment.FLAG_RST
        } else {
            sequence = 0
            acknowledgment = segment.sequence + segment.segmentLength
            flags = TcpSegment.FLAG_RST or TcpSegment.FLAG_ACK
        }
        sink.write(
            PacketBuilder.tcp(
                packet.version,
                packet.destination,
                packet.source,
                segment.destinationPort,
                segment.sourcePort,
                sequence,
                acknowledgment,
                flags,
                0
            )
        )
    }

    private fun submit(query: ByteArray, reply: (ByteArray) -> Unit) {
        try {
            executor.execute {
                val answer = resolveOrServFail(query)
                try {
                    reply(answer)
                } catch (e: RuntimeException) {
                    // A reply that cannot be built is dropped; the client retries.
                }
            }
        } catch (e: RejectedExecutionException) {
            // Too many queries are waiting. Fail this one quickly instead of queueing forever.
            reply(DnsMessage.servFail(query))
        }
    }

    private fun resolveOrServFail(query: ByteArray): ByteArray =
        try {
            val answer = resolver.resolve(query)
            onUpstreamResult?.invoke(true)
            answer
        } catch (e: IOException) {
            onUpstreamResult?.invoke(false)
            DnsMessage.servFail(query)
        } catch (e: RuntimeException) {
            onUpstreamResult?.invoke(false)
            DnsMessage.servFail(query)
        }

    private fun sweep() {
        val now = System.nanoTime()
        for ((key, session) in sessions) {
            if (session.isClosed()) {
                sessions.remove(key, session)
            } else if (session.isIdle(now, SESSION_IDLE_NANOS)) {
                session.abort()
                sessions.remove(key, session)
            }
        }
    }

    private data class FlowKey(
        val version: Int,
        val client: ByteBuffer,
        val clientPort: Int,
        val serverPort: Int
    )
}

private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
}
