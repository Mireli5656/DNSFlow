package com.dnsflow.app.dns

import com.dnsflow.app.config.ResolverEndpoint
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private const val DNS_MESSAGE_TYPE = "application/dns-message"

/**
 * DNS over HTTPS (RFC 8484).
 *
 * TLS uses the system trust store and OkHttp's standard host name verification; neither is
 * relaxed anywhere. Queries and answers are never logged.
 */
class DohResolver(
    private val endpoint: ResolverEndpoint,
    private val network: UpstreamNetwork
) : DnsResolver {

    private val url = requireNotNull(endpoint.dohUrl) { "A DoH endpoint needs a URL" }.toHttpUrl()
    private val mediaType = DNS_MESSAGE_TYPE.toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(ProtectedSocketFactory(network))
        .dns(BootstrapDns())
        .proxy(Proxy.NO_PROXY)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun resolve(query: ByteArray): ByteArray {
        requireValidQuery(query)

        val request = Request.Builder()
            .url(url)
            .header("Accept", DNS_MESSAGE_TYPE)
            .post(query.toRequestBody(mediaType))
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH server answered with HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("DoH server sent an empty response")
            val contentType = body.contentType()
            if (contentType == null ||
                contentType.type != "application" ||
                contentType.subtype != "dns-message"
            ) {
                throw IOException("DoH server did not return a DNS message")
            }

            val answer = body.byteStream().use { readLimited(it) }
            validateDnsResponse(query, answer)
            answer
        }
    }

    override fun reset() {
        client.connectionPool.evictAll()
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * Finds the resolver without asking the VPN's own DNS. The built-in bootstrap addresses are
     * used when there are any; otherwise the name is looked up on the underlying network.
     */
    private inner class BootstrapDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals(endpoint.host, ignoreCase = true) &&
                endpoint.bootstrapIps.isNotEmpty()
            ) {
                // The addresses are validated IP literals, so this never triggers a DNS lookup.
                return endpoint.bootstrapIps.map { InetAddress.getByName(it) }
            }
            return network.resolve(hostname)
        }
    }
}

/** Creates sockets that are exempt from the VPN before they connect. */
private class ProtectedSocketFactory(private val network: UpstreamNetwork) : SocketFactory() {

    override fun createSocket(): Socket = newProtectedSocket(null)

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(network.resolve(host).first(), port), null)

    override fun createSocket(
        host: String,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = connected(
        InetSocketAddress(network.resolve(host).first(), port),
        InetSocketAddress(localAddress, localPort)
    )

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port), null)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = connected(
        InetSocketAddress(address, port),
        InetSocketAddress(localAddress, localPort)
    )

    /** Binds to [local] first when one is given, then exempts the socket from the VPN. */
    private fun newProtectedSocket(local: InetSocketAddress?): Socket {
        val socket = Socket()
        try {
            if (local != null) socket.bind(local)
            if (!network.protect(socket)) {
                throw IOException("Could not exempt the socket from the VPN")
            }
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress?): Socket {
        val socket = newProtectedSocket(local)
        try {
            socket.connect(remote)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }
}

private fun readLimited(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        out.write(buffer, 0, count)
        if (out.size() > MAX_DNS_MESSAGE_SIZE) {
            throw IOException("DoH response is too large")
        }
    }
    return out.toByteArray()
}
