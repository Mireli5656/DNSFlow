package com.dnsflow.app.vpn

const val DNS_PORT = 53

/**
 * The addresses of the tunnel. Only the two server addresses are routed into it, so the tunnel
 * carries DNS traffic and nothing else.
 */
object VirtualDns {
    const val CLIENT_V4 = "10.111.222.1"
    const val SERVER_V4 = "10.111.222.2"
    const val PREFIX_V4 = 24
    const val CLIENT_V6 = "fd00:111:222::1"
    const val SERVER_V6 = "fd00:111:222::2"
    const val PREFIX_V6 = 64

    private val serverV4Bytes = byteArrayOf(10, 111, 222.toByte(), 2)
    private val serverV6Bytes = byteArrayOf(
        0xFD.toByte(), 0x00, 0x01, 0x11, 0x02, 0x22, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02
    )

    fun isServerAddress(address: ByteArray): Boolean =
        address.contentEquals(serverV4Bytes) || address.contentEquals(serverV6Bytes)
}

/** Where finished packets go: the tunnel. [write] never throws. */
interface PacketSink {
    fun write(packet: ByteArray)
}

/** An IPv4 or IPv6 packet that carries TCP or UDP. Anything else is rejected by [parse]. */
class IpPacket private constructor(
    val version: Int,
    val protocol: Int,
    val source: ByteArray,
    val destination: ByteArray,
    private val transport: ByteArray
) {

    internal fun transportBytes(): ByteArray = transport

    companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17

        /** Returns null for packets DNSFlow does not handle (fragments, other protocols, junk). */
        fun parse(buffer: ByteArray, length: Int): IpPacket? {
            if (length < 1 || length > buffer.size) return null
            return when ((buffer[0].toInt() and 0xF0) shr 4) {
                4 -> parseV4(buffer, length)
                6 -> parseV6(buffer, length)
                else -> null
            }
        }

        private fun parseV4(b: ByteArray, length: Int): IpPacket? {
            if (length < 20) return null
            val headerLength = (b[0].toInt() and 0x0F) * 4
            if (headerLength < 20 || headerLength > length) return null
            val totalLength = u16(b, 2)
            if (totalLength < headerLength || totalLength > length) return null

            // Fragments are dropped. DNS messages that fit the tunnel never need them.
            val fragmentField = ((b[6].toInt() and 0x3F) shl 8) or (b[7].toInt() and 0xFF)
            if (fragmentField != 0) return null

            val protocol = b[9].toInt() and 0xFF
            if (protocol != PROTOCOL_TCP && protocol != PROTOCOL_UDP) return null

            return IpPacket(
                version = 4,
                protocol = protocol,
                source = b.copyOfRange(12, 16),
                destination = b.copyOfRange(16, 20),
                transport = b.copyOfRange(headerLength, totalLength)
            )
        }

        private fun parseV6(b: ByteArray, length: Int): IpPacket? {
            if (length < 40) return null
            val payloadLength = u16(b, 4)
            if (40 + payloadLength > length) return null

            // Packets with extension headers are dropped; DNS queries do not use them.
            val nextHeader = b[6].toInt() and 0xFF
            if (nextHeader != PROTOCOL_TCP && nextHeader != PROTOCOL_UDP) return null

            return IpPacket(
                version = 6,
                protocol = nextHeader,
                source = b.copyOfRange(8, 24),
                destination = b.copyOfRange(24, 40),
                transport = b.copyOfRange(40, 40 + payloadLength)
            )
        }
    }
}

class UdpDatagram private constructor(
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray
) {
    companion object {
        fun parse(packet: IpPacket): UdpDatagram? {
            if (packet.protocol != IpPacket.PROTOCOL_UDP) return null
            val t = packet.transportBytes()
            if (t.size < 8) return null
            val udpLength = u16(t, 4)
            if (udpLength < 8 || udpLength > t.size) return null
            return UdpDatagram(
                sourcePort = u16(t, 0),
                destinationPort = u16(t, 2),
                payload = t.copyOfRange(8, udpLength)
            )
        }
    }
}

class TcpSegment private constructor(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequence: Int,
    val acknowledgment: Int,
    val flags: Int,
    val window: Int,
    val payload: ByteArray
) {
    val fin: Boolean get() = (flags and FLAG_FIN) != 0
    val syn: Boolean get() = (flags and FLAG_SYN) != 0
    val rst: Boolean get() = (flags and FLAG_RST) != 0
    val ack: Boolean get() = (flags and FLAG_ACK) != 0

    /** Sequence numbers this segment uses: its data plus one each for SYN and FIN. */
    val segmentLength: Int
        get() = payload.size + (if (syn) 1 else 0) + (if (fin) 1 else 0)

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10

        fun parse(packet: IpPacket): TcpSegment? {
            if (packet.protocol != IpPacket.PROTOCOL_TCP) return null
            val t = packet.transportBytes()
            if (t.size < 20) return null
            val headerLength = ((t[12].toInt() and 0xF0) shr 4) * 4
            if (headerLength < 20 || headerLength > t.size) return null
            return TcpSegment(
                sourcePort = u16(t, 0),
                destinationPort = u16(t, 2),
                sequence = u32(t, 4),
                acknowledgment = u32(t, 8),
                flags = t[13].toInt() and 0xFF,
                window = u16(t, 14),
                payload = t.copyOfRange(headerLength, t.size)
            )
        }
    }
}

/** Builds complete IP packets with correct header and checksum fields. */
object PacketBuilder {

    private const val TTL = 64

    fun udp(
        version: Int,
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray
    ): ByteArray {
        val transportLength = 8 + payload.size
        val offset = ipHeaderLength(version)
        val packet = ByteArray(offset + transportLength)

        putU16(packet, offset, sourcePort)
        putU16(packet, offset + 2, destinationPort)
        putU16(packet, offset + 4, transportLength)
        System.arraycopy(payload, 0, packet, offset + 8, payload.size)

        var checksum = transportChecksum(
            source, destination, IpPacket.PROTOCOL_UDP, packet, offset, transportLength
        )
        if (checksum == 0) checksum = 0xFFFF
        putU16(packet, offset + 6, checksum)

        writeIpHeader(packet, version, source, destination, IpPacket.PROTOCOL_UDP, transportLength)
        return packet
    }

    /** [mss] greater than zero adds the maximum segment size option (used on SYN-ACK). */
    fun tcp(
        version: Int,
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgment: Int,
        flags: Int,
        window: Int,
        payload: ByteArray = ByteArray(0),
        mss: Int = 0
    ): ByteArray {
        val headerLength = if (mss > 0) 24 else 20
        val transportLength = headerLength + payload.size
        val offset = ipHeaderLength(version)
        val packet = ByteArray(offset + transportLength)

        putU16(packet, offset, sourcePort)
        putU16(packet, offset + 2, destinationPort)
        putU32(packet, offset + 4, sequence)
        putU32(packet, offset + 8, acknowledgment)
        packet[offset + 12] = ((headerLength / 4) shl 4).toByte()
        packet[offset + 13] = flags.toByte()
        putU16(packet, offset + 14, window)
        if (mss > 0) {
            packet[offset + 20] = 2
            packet[offset + 21] = 4
            putU16(packet, offset + 22, mss)
        }
        System.arraycopy(payload, 0, packet, offset + headerLength, payload.size)

        val checksum = transportChecksum(
            source, destination, IpPacket.PROTOCOL_TCP, packet, offset, transportLength
        )
        putU16(packet, offset + 16, checksum)

        writeIpHeader(packet, version, source, destination, IpPacket.PROTOCOL_TCP, transportLength)
        return packet
    }

    private fun ipHeaderLength(version: Int): Int = if (version == 4) 20 else 40

    private fun writeIpHeader(
        packet: ByteArray,
        version: Int,
        source: ByteArray,
        destination: ByteArray,
        protocol: Int,
        transportLength: Int
    ) {
        if (version == 4) {
            packet[0] = 0x45
            putU16(packet, 2, 20 + transportLength)
            packet[6] = 0x40 // Don't Fragment
            packet[8] = TTL.toByte()
            packet[9] = protocol.toByte()
            System.arraycopy(source, 0, packet, 12, 4)
            System.arraycopy(destination, 0, packet, 16, 4)
            putU16(packet, 10, fold(sum(packet, 0, 20, 0L)))
        } else {
            packet[0] = 0x60
            putU16(packet, 4, transportLength)
            packet[6] = protocol.toByte()
            packet[7] = TTL.toByte()
            System.arraycopy(source, 0, packet, 8, 16)
            System.arraycopy(destination, 0, packet, 24, 16)
        }
    }

    /** Checksum over the pseudo header and the transport segment (checksum field is zero). */
    private fun transportChecksum(
        source: ByteArray,
        destination: ByteArray,
        protocol: Int,
        packet: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var total = sum(source, 0, source.size, 0L)
        total = sum(destination, 0, destination.size, total)
        total += protocol.toLong()
        total += length.toLong()
        total = sum(packet, offset, length, total)
        return fold(total)
    }

    private fun sum(data: ByteArray, offset: Int, length: Int, initial: Long): Long {
        var total = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            total += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            total += (data[i].toInt() and 0xFF) shl 8
        }
        return total
    }

    private fun fold(total: Long): Int {
        var t = total
        while ((t ushr 16) != 0L) {
            t = (t and 0xFFFFL) + (t ushr 16)
        }
        return (t.inv() and 0xFFFFL).toInt()
    }

    private fun putU16(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value ushr 8).toByte()
        b[offset + 1] = value.toByte()
    }

    private fun putU32(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value ushr 24).toByte()
        b[offset + 1] = (value ushr 16).toByte()
        b[offset + 2] = (value ushr 8).toByte()
        b[offset + 3] = value.toByte()
    }
}

private fun u16(b: ByteArray, offset: Int): Int =
    ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

private fun u32(b: ByteArray, offset: Int): Int =
    ((b[offset].toInt() and 0xFF) shl 24) or
        ((b[offset + 1].toInt() and 0xFF) shl 16) or
        ((b[offset + 2].toInt() and 0xFF) shl 8) or
        (b[offset + 3].toInt() and 0xFF)
