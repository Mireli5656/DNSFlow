package com.dnsflow.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Watches the device's real networks (Wi-Fi, cellular, Ethernet) and ignores VPNs. It keeps
 * track of the best one in [currentNetwork].
 *
 * [onChanged] is called whenever the best network changes: with true if there is one, with
 * false if connectivity was lost. It runs on a system thread.
 */
class NetworkMonitor(
    context: Context,
    private val onChanged: (available: Boolean) -> Unit
) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val candidates = HashMap<Network, NetworkCapabilities>()

    @Volatile
    var currentNetwork: Network? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            val capabilities = connectivity.getNetworkCapabilities(network)
            refresh {
                if (capabilities != null) candidates[network] = capabilities
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            refresh { candidates[network] = networkCapabilities }
        }

        override fun onLost(network: Network) {
            refresh { candidates.remove(network) }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        seed()
    }

    fun stop() {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // It was not registered.
        }
        synchronized(lock) {
            candidates.clear()
            currentNetwork = null
        }
    }

    /** Callbacks arrive late, so networks that already exist are read directly. */
    @Suppress("DEPRECATION")
    private fun seed() {
        refresh {
            for (network in connectivity.allNetworks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                val usable =
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (usable) candidates[network] = capabilities
            }
        }
    }

    private fun refresh(mutate: () -> Unit) {
        var changed = false
        var available = false

        synchronized(lock) {
            mutate()

            val bestEntry = candidates.entries.maxByOrNull { score(it.value) }
            val current = currentNetwork
            val currentScore = current?.let { candidates[it] }?.let { score(it) }

            // Keep the current network unless another one is clearly better, to avoid flapping.
            val keepCurrent = bestEntry != null &&
                currentScore != null &&
                currentScore >= score(bestEntry.value)
            val best = if (keepCurrent) current else bestEntry?.key

            if (best != currentNetwork) {
                currentNetwork = best
                changed = true
            }
            available = best != null
        }

        if (changed) onChanged(available)
    }

    private fun score(capabilities: NetworkCapabilities): Int {
        var score = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            score += 100
        }
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> score += 30
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> score += 20
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> score += 10
        }
        return score
    }
}
