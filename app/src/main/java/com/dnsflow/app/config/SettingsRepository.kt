package com.dnsflow.app.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableConfig = MutableStateFlow(load())

    val config: StateFlow<DnsConfig> = mutableConfig.asStateFlow()

    @Synchronized
    fun update(transform: (DnsConfig) -> DnsConfig) {
        val updated = transform(mutableConfig.value)
        save(updated)
        mutableConfig.value = updated
    }

    private fun load(): DnsConfig {
        val defaults = DnsConfig()

        var providerId = prefs.getString(KEY_PROVIDER_ID, null) ?: defaults.providerId
        if (providerId != DnsProviders.CUSTOM_ID && DnsProviders.byId(providerId) == null) {
            providerId = DnsProviders.DEFAULT_ID
        }

        val storedProtocol = prefs.getString(KEY_PROTOCOL, null)
        val protocol = DnsProtocol.entries.firstOrNull { it.name == storedProtocol }
            ?: defaults.protocol

        var ipv4Enabled = prefs.getBoolean(KEY_IPV4, defaults.ipv4Enabled)
        val ipv6Enabled = prefs.getBoolean(KEY_IPV6, defaults.ipv6Enabled)
        if (!ipv4Enabled && !ipv6Enabled) {
            ipv4Enabled = true
        }

        return DnsConfig(
            providerId = providerId,
            protocol = protocol,
            customDohUrl = prefs.getString(KEY_CUSTOM_DOH_URL, null) ?: defaults.customDohUrl,
            customDotHost = prefs.getString(KEY_CUSTOM_DOT_HOST, null) ?: defaults.customDotHost,
            customDotPort = prefs.getInt(KEY_CUSTOM_DOT_PORT, defaults.customDotPort),
            customBootstrapIp = prefs.getString(KEY_CUSTOM_BOOTSTRAP_IP, null)
                ?: defaults.customBootstrapIp,
            ipv4Enabled = ipv4Enabled,
            ipv6Enabled = ipv6Enabled
        )
    }

    private fun save(config: DnsConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER_ID, config.providerId)
            .putString(KEY_PROTOCOL, config.protocol.name)
            .putString(KEY_CUSTOM_DOH_URL, config.customDohUrl)
            .putString(KEY_CUSTOM_DOT_HOST, config.customDotHost)
            .putInt(KEY_CUSTOM_DOT_PORT, config.customDotPort)
            .putString(KEY_CUSTOM_BOOTSTRAP_IP, config.customBootstrapIp)
            .putBoolean(KEY_IPV4, config.ipv4Enabled)
            .putBoolean(KEY_IPV6, config.ipv6Enabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dnsflow_settings"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_CUSTOM_DOH_URL = "custom_doh_url"
        private const val KEY_CUSTOM_DOT_HOST = "custom_dot_host"
        private const val KEY_CUSTOM_DOT_PORT = "custom_dot_port"
        private const val KEY_CUSTOM_BOOTSTRAP_IP = "custom_bootstrap_ip"
        private const val KEY_IPV4 = "ipv4_enabled"
        private const val KEY_IPV6 = "ipv6_enabled"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
