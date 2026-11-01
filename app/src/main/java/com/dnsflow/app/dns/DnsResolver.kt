package com.dnsflow.app.dns

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

const val DNS_HEADER_SIZE = 12
const val MAX_DNS_MESSAGE_SIZE = 65_535

/**
 * Sends DNS queries to one upstream resolver over an encrypted transport.
 *
 * Calls block, so run them on a worker thread. They may be called from several threads at once.
 * A failed call throws [IOException]; the caller decides how to answer the client.
 */
interface DnsResolver : Closeable {

    /** Sends one wire-format DNS query and returns the wire-format response. */
    @Throws(IOException::class)
    fun resolve(query: ByteArray): ByteArray

    /** Drops pooled connections, for example after the underlying network changed. */
    fun reset()
}

/**
 * The device's real network, outside the VPN. The VPN service provides the implementation.
 */
interface UpstreamNetwork {

    /**
     * Exempts [socket] from the VPN so its traffic reaches the resolver directly instead of
     * looping back into the tunnel. Must be called before the socket connects.
     */
    fun protect(socket: Socket): Boolean

    /** Looks up [host] on the underlying network, not through the VPN's own DNS. */
    @Throws(IOException::class)
    fun resolve(host: String): List<InetAddress>
}

@Throws(IOException::class)
internal fun requireValidQuery(query: ByteArray) {
    if (query.size < DNS_HEADER_SIZE || query.size > MAX_DNS_MESSAGE_SIZE) {
        throw IOException("Invalid DNS query size")
    }
}

/** Checks that [response] is a DNS response that belongs to [query]. */
@Throws(IOException::class)
internal fun validateDnsResponse(query: ByteArray, response: ByteArray) {
    if (response.size < DNS_HEADER_SIZE) {
        throw IOException("DNS response is too short")
    }
    if (response[0] != query[0] || response[1] != query[1]) {
        throw IOException("DNS response ID does not match the query")
    }
    val isResponse = (response[2].toInt() and 0x80) != 0
    if (!isResponse) {
        throw IOException("Upstream did not send a DNS response")
    }
}
