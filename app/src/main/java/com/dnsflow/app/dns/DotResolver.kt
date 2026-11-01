package com.dnsflow.app.dns

import com.dnsflow.app.config.ResolverEndpoint
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val CONNECT_TIMEOUT_MS = 4_000
private const val CONNECT_BUDGET_MS = 8_000L
private const val IO_TIMEOUT_MS = 5_000
private const val PERMIT_WAIT_MS = 5_000L
private const val MAX_PARALLEL_CONNECTIONS = 6
private const val MAX_IDLE_CONNECTIONS = 4
private val IDLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10)

/**
 * DNS over TLS (RFC 7858).
 *
 * The server certificate is validated against the system trust store and checked against the
 * server's host name; neither check is ever relaxed. Queries and answers are never logged.
 */
class DotResolver(
    private val endpoint: ResolverEndpoint,
    private val network: UpstreamNetwork
) : DnsResolver {

    private val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    private val idle = java.util.ArrayDeque<DotConnection>()
    private val permits = Semaphore(MAX_PARALLEL_CONNECTIONS, true)
    private val generation = AtomicInteger()

    @Volatile
    private var closed = false

    override fun resolve(query: ByteArray): ByteArray {
        requireValidQuery(query)
        if (closed) throw IOException("The DoT resolver is closed")

        acquirePermit()
        try {
            return resolveWithPermit(query)
        } finally {
            permits.release()
        }
    }

    override fun reset() {
        generation.incrementAndGet()
        drainIdle()
    }

    override fun close() {
        closed = true
        drainIdle()
    }

    private fun acquirePermit() {
        try {
            if (!permits.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("Too many DNS queries are waiting for the DoT server")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for a DoT connection")
        }
    }

    private fun resolveWithPermit(query: ByteArray): ByteArray {
        val reused = takeIdle()
        if (reused != null) {
            try {
                return exchange(reused, query)
            } catch (e: IOException) {
                // Most likely the server closed an idle connection. Retry once on a new one.
                reused.closeQuietly()
            }
        }

        val fresh = connect()
        try {
            return exchange(fresh, query)
        } catch (e: IOException) {
            fresh.closeQuietly()
            throw e
        }
    }

    private fun exchange(connection: DotConnection, query: ByteArray): ByteArray {
        val answer = connection.exchange(query)
        validateDnsResponse(query, answer)
        release(connection)
        return answer
    }

    private fun takeIdle(): DotConnection? = synchronized(idle) {
        val now = System.nanoTime()
        var found: DotConnection? = null
        while (found == null) {
            val candidate = idle.pollFirst() ?: break
            if (candidate.isFresh(now)) {
                found = candidate
            } else {
                candidate.closeQuietly()
            }
        }
        found
    }

    private fun release(connection: DotConnection) {
        if (closed || connection.generation != generation.get()) {
            connection.closeQuietly()
            return
        }
        connection.touch()
        val overflow: DotConnection? = synchronized(idle) {
            idle.addFirst(connection)
            if (idle.size > MAX_IDLE_CONNECTIONS) idle.pollLast() else null
        }
        overflow?.closeQuietly()
    }

    private fun drainIdle() {
        val connections = synchronized(idle) {
            val copy = idle.toList()
            idle.clear()
            copy
        }
        connections.forEach { it.closeQuietly() }
    }

    private fun connect(): DotConnection {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_BUDGET_MS)
        var lastError: IOException? = null

        for (address in candidateAddresses()) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) break
            val timeoutMs = minOf(CONNECT_TIMEOUT_MS.toLong(), remainingMs).toInt()
            try {
                return openTls(address, timeoutMs)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Could not reach the DoT server")
    }

    private fun candidateAddresses(): List<InetAddress> =
        if (endpoint.bootstrapIps.isNotEmpty()) {
            // The addresses are validated IP literals, so this never triggers a DNS lookup.
            endpoint.bootstrapIps.map { InetAddress.getByName(it) }
        } else {
            network.resolve(endpoint.host)
        }

    private fun openTls(address: InetAddress, connectTimeoutMs: Int): DotConnection {
        val raw = Socket()
        var tls: SSLSocket? = null
        try {
            if (!network.protect(raw)) {
                throw IOException("Could not exempt the socket from the VPN")
            }
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(address, endpoint.port), connectTimeoutMs)
            raw.soTimeout = IO_TIMEOUT_MS

            val socket = sslSocketFactory.createSocket(
                raw,
                endpoint.host,
                endpoint.port,
                true
            ) as SSLSocket
            tls = socket

            configure(socket)
            socket.startHandshake()
            verifyHostName(socket)
            return DotConnection(socket, generation.get())
        } catch (e: IOException) {
            closeQuietly(tls ?: raw)
            throw e
        } catch (e: RuntimeException) {
            closeQuietly(tls ?: raw)
            throw IOException("Could not set up the TLS connection", e)
        }
    }

    private fun configure(socket: SSLSocket) {
        val protocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (protocols.isEmpty()) {
            throw SSLException("TLS 1.2 or newer is not available on this device")
        }
        val params = socket.sslParameters
        params.protocols = protocols.toTypedArray()
        params.endpointIdentificationAlgorithm = "HTTPS"
        socket.sslParameters = params
        socket.soTimeout = IO_TIMEOUT_MS
    }

    /** SSLSocket does not check the host name by itself, so it is checked here as well. */
    private fun verifyHostName(socket: SSLSocket) {
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        if (!verifier.verify(endpoint.host, socket.session)) {
            throw SSLPeerUnverifiedException(
                "The DoT server certificate does not match its host name"
            )
        }
    }
}

/** One TLS connection. It serves a single query at a time. */
private class DotConnection(
    private val socket: SSLSocket,
    val generation: Int
) {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = socket.outputStream
    private var lastUsedNanos = System.nanoTime()

    fun exchange(query: ByteArray): ByteArray {
        // The 2-byte length and the message go out in one write, so they share a TLS record.
        val framed = ByteArray(2 + query.size)
        framed[0] = (query.size ushr 8).toByte()
        framed[1] = query.size.toByte()
        System.arraycopy(query, 0, framed, 2, query.size)
        output.write(framed)
        output.flush()

        val length = input.readUnsignedShort()
        if (length < DNS_HEADER_SIZE) {
            throw IOException("DoT response is too short")
        }
        val answer = ByteArray(length)
        input.readFully(answer)
        return answer
    }

    fun touch() {
        lastUsedNanos = System.nanoTime()
    }

    fun isFresh(now: Long): Boolean =
        !socket.isClosed && now - lastUsedNanos < IDLE_TIMEOUT_NANOS

    fun closeQuietly() {
        closeQuietly(socket)
    }
}

private fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (ignored: IOException) {
        // Nothing useful can be done if closing fails.
    }
}
