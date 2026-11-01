package com.dnsflow.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dnsflow.app.config.DnsConfig
import com.dnsflow.app.config.DnsProtocol
import com.dnsflow.app.config.DnsProviders
import com.dnsflow.app.config.SettingsRepository
import com.dnsflow.app.state.ConnectionState
import com.dnsflow.app.state.ConnectionStateHolder
import com.dnsflow.app.vpn.DnsVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private const val DEFAULT_DOT_PORT = 853

data class MainUiState(
    val config: DnsConfig,
    val connection: ConnectionState,
    val notice: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository.getInstance(application)
    private val notice = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        repository.config,
        ConnectionStateHolder.state,
        notice
    ) { config, connection, message ->
        MainUiState(config = config, connection = connection, notice = message)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(
            config = repository.config.value,
            connection = ConnectionStateHolder.state.value
        )
    )

    fun isConnectionActive(): Boolean {
        val current = ConnectionStateHolder.state.value
        return current is ConnectionState.Connecting || current is ConnectionState.Connected
    }

    /** Validates the saved settings. Returns false and shows an error if they are unusable. */
    fun prepareToConnect(): Boolean {
        val error = repository.config.value.validate()
        if (error != null) {
            ConnectionStateHolder.set(ConnectionState.Error(error))
            return false
        }
        notice.value = null
        ConnectionStateHolder.set(ConnectionState.Connecting)
        return true
    }

    fun onVpnPermissionDenied() {
        ConnectionStateHolder.set(ConnectionState.Error("VPN permission was not granted."))
    }

    fun disconnect() {
        DnsVpnService.stop(getApplication<Application>())
        ConnectionStateHolder.set(ConnectionState.Disconnected)
    }

    fun dismissNotice() {
        notice.value = null
    }

    fun selectProvider(providerId: String) {
        notice.value = tryApply(repository.config.value.copy(providerId = providerId))
    }

    fun selectProtocol(protocol: DnsProtocol) {
        notice.value = tryApply(repository.config.value.copy(protocol = protocol))
    }

    fun setIpv4Enabled(enabled: Boolean) {
        val current = repository.config.value
        if (!enabled && !current.ipv6Enabled) {
            notice.value = "At least one IP version must stay enabled."
            return
        }
        notice.value = tryApply(current.copy(ipv4Enabled = enabled))
    }

    fun setIpv6Enabled(enabled: Boolean) {
        val current = repository.config.value
        if (!enabled && !current.ipv4Enabled) {
            notice.value = "At least one IP version must stay enabled."
            return
        }
        notice.value = tryApply(current.copy(ipv6Enabled = enabled))
    }

    /** Returns an error message, or null when the custom server was saved and selected. */
    fun saveCustomServer(
        dohUrl: String,
        dotHost: String,
        dotPort: String,
        bootstrapIp: String
    ): String? {
        val portText = dotPort.trim()
        val port = if (portText.isEmpty()) {
            DEFAULT_DOT_PORT
        } else {
            portText.toIntOrNull() ?: return "DoT port must be a number."
        }

        val candidate = repository.config.value.copy(
            providerId = DnsProviders.CUSTOM_ID,
            customDohUrl = dohUrl.trim(),
            customDotHost = dotHost.trim(),
            customDotPort = port,
            customBootstrapIp = bootstrapIp.trim()
        )
        notice.value = null
        return tryApply(candidate)
    }

    /**
     * Saves the config only if it is valid. While connected, the VPN service is told to
     * restart so the new settings take effect. Returns an error message or null.
     */
    private fun tryApply(newConfig: DnsConfig): String? {
        val error = newConfig.validate()
        if (error != null) return error

        repository.update { newConfig }
        if (isConnectionActive()) {
            DnsVpnService.start(getApplication<Application>())
        }
        return null
    }
}
