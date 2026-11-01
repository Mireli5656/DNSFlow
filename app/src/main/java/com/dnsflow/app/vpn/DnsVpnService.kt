package com.dnsflow.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.dnsflow.app.MainActivity
import com.dnsflow.app.R
import com.dnsflow.app.config.DnsConfig
import com.dnsflow.app.config.SettingsRepository
import com.dnsflow.app.dns.DnsResolver
import com.dnsflow.app.dns.ResolverFactory
import com.dnsflow.app.dns.UpstreamNetwork
import com.dnsflow.app.network.NetworkMonitor
import com.dnsflow.app.state.ConnectionState
import com.dnsflow.app.state.ConnectionStateHolder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * The VPN service. The tunnel only routes the virtual DNS addresses, so it carries DNS traffic
 * and nothing else. Every query is sent to the chosen resolver over DoH or DoT.
 *
 * Start and restart with [start], stop with [stop]. Queries and answers are never logged.
 */
class DnsVpnService : VpnService() {

    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dnsflow-control").apply { isDaemon = true }
    }
    private val upstream = Upstream()
    private val generation = AtomicInteger()
    private val consecutiveFailures = AtomicInteger()
    private lateinit var settings: SettingsRepository

    // The running session. These change only while holding the service lock.
    @Volatile
    private var monitor: NetworkMonitor? = null

    @Volatile
    private var resolver: DnsResolver? = null
    private var forwarder: PacketForwarder? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var readThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // ACTION_START, or the system starting an always-on VPN.
        if (!enterForeground()) {
            ConnectionStateHolder.set(
                ConnectionState.Error("Android did not allow the VPN to start right now.")
            )
            stopSelf()
            return START_NOT_STICKY
        }
        controlExecutor.execute { startSession() }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user removed the permission.
        ConnectionStateHolder.set(
            ConnectionState.Error("The VPN was turned off by Android or another VPN app.")
        )
        super.onRevoke()
    }

    override fun onDestroy() {
        controlExecutor.shutdownNow()
        stopSession()
        if (ConnectionStateHolder.state.value !is ConnectionState.Error) {
            ConnectionStateHolder.set(ConnectionState.Disconnected)
        }
        super.onDestroy()
    }

    // region Session

    @Synchronized
    private fun startSession() {
        stopSession()

        val config = settings.config.value
        val configError = config.validate()
        if (configError != null) {
            fail(configError)
            return
        }

        val sessionId = generation.incrementAndGet()
        consecutiveFailures.set(0)
        ConnectionStateHolder.set(ConnectionState.Connecting)

        try {
            val newMonitor = NetworkMonitor(this) { onUnderlyingNetworkChanged(sessionId) }
            monitor = newMonitor
            newMonitor.start()

            val newResolver = ResolverFactory.create(config, upstream)
            resolver = newResolver

            val tun = establishTunnel(config)
            if (tun == null) {
                fail(MESSAGE_PERMISSION_REVOKED)
                return
            }
            tunnel = tun

            val newForwarder = PacketForwarder(
                newResolver,
                TunSink(FileOutputStream(tun.fileDescriptor))
            )
            newForwarder.onUpstreamResult = { success -> onUpstreamResult(sessionId, success) }
            forwarder = newForwarder

            val input = FileInputStream(tun.fileDescriptor)
            val thread = Thread(
                { readLoop(sessionId, input, newForwarder) },
                "dnsflow-tunnel"
            )
            thread.isDaemon = true
            readThread = thread
            thread.start()

            startProbe(sessionId, newResolver)
        } catch (e: SecurityException) {
            fail(MESSAGE_PERMISSION_REVOKED)
        } catch (e: IOException) {
            fail(MESSAGE_START_FAILED)
        } catch (e: RuntimeException) {
            fail(MESSAGE_START_FAILED)
        }
    }

    @Synchronized
    private fun stopSession() {
        // Late callbacks from the old session check this number and do nothing.
        generation.incrementAndGet()

        monitor?.stop()
        monitor = null

        forwarder?.close()
        forwarder = null

        // Closing the tunnel also ends the blocking read in the tunnel thread.
        try {
            tunnel?.close()
        } catch (e: IOException) {
            // Nothing useful can be done if closing fails.
        }
        tunnel = null

        resolver?.close()
        resolver = null

        val thread = readThread
        readThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Synchronized
    private fun fail(message: String) {
        stopSession()
        ConnectionStateHolder.set(ConnectionState.Error(message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishTunnel(config: DnsConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(TUN_MTU)
            .setBlocking(true)
            .setConfigureIntent(openAppIntent())

        if (config.ipv4Enabled) {
            builder.addAddress(VirtualDns.CLIENT_V4, VirtualDns.PREFIX_V4)
            builder.addDnsServer(VirtualDns.SERVER_V4)
            builder.addRoute(VirtualDns.SERVER_V4, 32)
        }
        if (config.ipv6Enabled) {
            builder.addAddress(VirtualDns.CLIENT_V6, VirtualDns.PREFIX_V6)
            builder.addDnsServer(VirtualDns.SERVER_V6)
            builder.addRoute(VirtualDns.SERVER_V6, 128)
        }

        // DNSFlow's own traffic to the resolver never goes through the tunnel, so it cannot loop.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            // Sockets are also protected one by one.
        }

        return builder.establish()
    }

    private fun readLoop(sessionId: Int, input: FileInputStream, forwarder: PacketForwarder) {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        try {
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length > 0) forwarder.handlePacket(buffer, length)
            }
        } catch (e: IOException) {
            // The tunnel was closed, by us or by the system.
        }
        if (generation.get() == sessionId) {
            onTunnelClosed(sessionId)
        }
    }

    @Synchronized
    private fun onTunnelClosed(sessionId: Int) {
        if (generation.get() == sessionId) {
            fail("The VPN tunnel closed unexpectedly.")
        }
    }

    // endregion

    // region Health

    /**
     * Sends one fixed test query (the root name servers) to make sure the resolver can be
     * reached before the VPN is shown as connected. It is not a user query.
     */
    private fun startProbe(sessionId: Int, resolver: DnsResolver) {
        val thread = Thread(
            { finishProbe(sessionId, probeError(resolver)) },
            "dnsflow-probe"
        )
        thread.isDaemon = true
        thread.start()
    }

    @Synchronized
    private fun finishProbe(sessionId: Int, error: String?) {
        if (generation.get() != sessionId) return
        if (error == null) {
            ConnectionStateHolder.set(ConnectionState.Connected)
        } else {
            fail(error)
        }
    }

    private fun probeError(resolver: DnsResolver): String? =
        try {
            resolver.resolve(buildProbeQuery())
            null
        } catch (e: IOException) {
            if (isTlsFailure(e)) MESSAGE_TLS_FAILED else MESSAGE_UNREACHABLE
        } catch (e: RuntimeException) {
            MESSAGE_UNREACHABLE
        }

    private fun buildProbeQuery(): ByteArray {
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        val query = ByteArray(17)
        query[0] = (id ushr 8).toByte()
        query[1] = id.toByte()
        query[2] = 0x01 // recursion desired
        query[5] = 1 // one question
        query[12] = 0 // the root name
        query[14] = 2 // type NS
        query[16] = 1 // class IN
        return query
    }

    private fun isTlsFailure(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is SSLException) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun onUpstreamResult(sessionId: Int, success: Boolean) {
        if (generation.get() != sessionId) return
        if (success) {
            consecutiveFailures.set(0)
        } else if (consecutiveFailures.incrementAndGet() % RESET_AFTER_FAILURES == 0) {
            // Repeated failures often mean stale connections. Start fresh ones.
            resolver?.reset()
        }
    }

    private fun onUnderlyingNetworkChanged(sessionId: Int) {
        if (generation.get() != sessionId) return
        consecutiveFailures.set(0)
        resolver?.reset()
    }

    // endregion

    // region Notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Returns false if Android refuses to start the foreground service. */
    private fun enterForeground(): Boolean =
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: RuntimeException) {
            false
        }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnect = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_dnsflow),
            "Disconnect",
            stopIntent
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dnsflow)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(openAppIntent())
            .addAction(disconnect)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // endregion

    /** Gives the resolvers a way around the tunnel and a way to find the resolver's address. */
    private inner class Upstream : UpstreamNetwork {

        override fun protect(socket: Socket): Boolean {
            if (!socket.isBound) {
                try {
                    // A plain Socket has no file descriptor until it is bound, and only a
                    // socket that has one can be protected.
                    socket.bind(null)
                } catch (e: IOException) {
                    return false
                }
            }
            return this@DnsVpnService.protect(socket)
        }

        override fun resolve(host: String): List<InetAddress> {
            val network = monitor?.currentNetwork ?: throw IOException("No network connection")
            return network.getAllByName(host).toList()
        }
    }

    companion object {
        const val ACTION_START = "com.dnsflow.app.action.START"
        const val ACTION_STOP = "com.dnsflow.app.action.STOP"

        private const val CHANNEL_ID = "dnsflow_connection"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1
        private const val SESSION_NAME = "DNSFlow"
        private const val TUN_MTU = 1500
        private const val MAX_PACKET_SIZE = 32_767
        private const val JOIN_TIMEOUT_MS = 1_000L
        private const val RESET_AFTER_FAILURES = 3

        private const val MESSAGE_PERMISSION_REVOKED =
            "VPN permission was revoked. Connect again to grant it."
        private const val MESSAGE_START_FAILED = "Could not start the VPN."
        private const val MESSAGE_UNREACHABLE =
            "Cannot reach the DNS server. Check your connection and the server settings."
        private const val MESSAGE_TLS_FAILED =
            "Could not set up a secure (TLS) connection to the DNS server. " +
                "Check the server settings."

        /** Starts the VPN, or restarts it so new settings take effect. */
        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DnsVpnService::class.java))
        }
    }
}

/** Writes finished packets to the tunnel. */
private class TunSink(private val output: FileOutputStream) : PacketSink {

    override fun write(packet: ByteArray) {
        try {
            synchronized(output) {
                output.write(packet)
            }
        } catch (e: IOException) {
            // The tunnel is closed; nothing more can be sent.
        }
    }
}
