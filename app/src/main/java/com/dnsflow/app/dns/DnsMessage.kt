package com.dnsflow.app.dns

/**
 * Minimal DNS wire-format helpers. They only read what DNSFlow needs and check every offset,
 * so a malformed packet is rejected instead of crashing the service.
 */
object DnsMessage {

    private const val FLAG_QR = 0x80
    private const val FLAG_TC = 0x02
    private const val FLAG_RD = 0x01
    private const val FLAG_RA = 0x80
    private const val OPCODE_MASK = 0x78
    private const val RCODE_NOERROR = 0
    private const val RCODE_SERVFAIL = 2
    private const val TYPE_OPT = 41

    private const val DEFAULT_UDP_SIZE = 512
    private const val MAX_UDP_SIZE = 1232

    /** True if [packet] looks like a DNS query: a full header, QR bit clear, a question. */
    fun isQuery(packet: ByteArray): Boolean =
        packet.size >= DNS_HEADER_SIZE &&
            (packet[2].toInt() and FLAG_QR) == 0 &&
            u16(packet, 4) >= 1

    /** A SERVFAIL answer for [query], used when the upstream resolver cannot be reached. */
    fun servFail(query: ByteArray): ByteArray = reply(query, RCODE_SERVFAIL, truncated = false)

    /**
     * Makes [answer] fit in one UDP reply. If it is too big, the client gets a truncated
     * answer (TC bit set) and retries over TCP, as the DNS standard requires.
     */
    fun fitForUdp(query: ByteArray, answer: ByteArray): ByteArray =
        if (answer.size <= maxUdpResponseSize(query)) {
            answer
        } else {
            reply(query, RCODE_NOERROR, truncated = true)
        }

    /** The largest UDP answer the client accepts, capped so it never needs IP fragmentation. */
    fun maxUdpResponseSize(query: ByteArray): Int {
        val advertised = ednsUdpSize(query) ?: DEFAULT_UDP_SIZE
        return advertised.coerceIn(DEFAULT_UDP_SIZE, MAX_UDP_SIZE)
    }

    private fun reply(query: ByteArray, rcode: Int, truncated: Boolean): ByteArray {
        val source = if (query.size < DNS_HEADER_SIZE) query.copyOf(DNS_HEADER_SIZE) else query

        val questionCount = u16(source, 4)
        val questionsEnd = skipQuestions(source, questionCount)
        val keepQuestions = questionsEnd > 0
        val out = source.copyOf(if (keepQuestions) questionsEnd else DNS_HEADER_SIZE)

        val flags = FLAG_QR or
            (source[2].toInt() and OPCODE_MASK) or
            (source[2].toInt() and FLAG_RD) or
            (if (truncated) FLAG_TC else 0)
        out[2] = flags.toByte()
        out[3] = (FLAG_RA or (rcode and 0x0F)).toByte()
        putU16(out, 4, if (keepQuestions) questionCount else 0)
        putU16(out, 6, 0)
        putU16(out, 8, 0)
        putU16(out, 10, 0)
        return out
    }

    /** Returns the UDP payload size from the query's EDNS OPT record, or null if there is none. */
    private fun ednsUdpSize(packet: ByteArray): Int? {
        if (packet.size < DNS_HEADER_SIZE) return null
        var offset = skipQuestions(packet, u16(packet, 4))
        if (offset < 0) return null

        val records = u16(packet, 6) + u16(packet, 8) + u16(packet, 10)
        repeat(records) {
            offset = skipName(packet, offset)
            if (offset < 0 || offset + 10 > packet.size) return null
            val type = u16(packet, offset)
            val recordClass = u16(packet, offset + 2)
            val dataLength = u16(packet, offset + 8)
            if (type == TYPE_OPT) return recordClass
            offset += 10 + dataLength
            if (offset > packet.size) return null
        }
        return null
    }

    /** Returns the offset just after the questions, or -1 if the message is malformed. */
    private fun skipQuestions(packet: ByteArray, count: Int): Int {
        var offset = DNS_HEADER_SIZE
        repeat(count) {
            offset = skipName(packet, offset)
            if (offset < 0 || offset + 4 > packet.size) return -1
            offset += 4
        }
        return offset
    }

    /** Returns the offset just after the name starting at [start], or -1 if it is malformed. */
    private fun skipName(packet: ByteArray, start: Int): Int {
        var offset = start
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            when {
                length == 0 -> return offset + 1
                (length and 0xC0) == 0xC0 -> return if (offset + 2 <= packet.size) offset + 2 else -1
                (length and 0xC0) != 0 -> return -1
                else -> {
                    offset += 1 + length
                }
            }
        }
        return -1
    }

    private fun u16(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value ushr 8).toByte()
        b[offset + 1] = value.toByte()
    }
}
