package com.dnsflow.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dnsflow.app.ui.MainScreen
import com.dnsflow.app.ui.MainViewModel
import com.dnsflow.app.ui.SettingsScreen
import com.dnsflow.app.ui.theme.DnsFlowTheme
import com.dnsflow.app.vpn.DnsVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                DnsVpnService.start(this)
            } else {
                viewModel.onVpnPermissionDenied()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The VPN works without notifications, so continue either way.
            requestVpnPermissionAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            DnsFlowTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                BackHandler(enabled = showSettings) {
                    showSettings = false
                }

                if (showSettings) {
                    SettingsScreen(
                        state = state,
                        onBack = { showSettings = false },
                        onSelectProvider = { viewModel.selectProvider(it) },
                        onProtocolSelected = { viewModel.selectProtocol(it) },
                        onIpv4Changed = { viewModel.setIpv4Enabled(it) },
                        onIpv6Changed = { viewModel.setIpv6Enabled(it) },
                        onSaveCustom = { dohUrl, dotHost, dotPort, bootstrapIp ->
                            viewModel.saveCustomServer(dohUrl, dotHost, dotPort, bootstrapIp)
                        },
                        onDismissNotice = { viewModel.dismissNotice() }
                    )
                } else {
                    MainScreen(
                        state = state,
                        onToggleConnection = { onToggleConnection() },
                        onProtocolSelected = { viewModel.selectProtocol(it) },
                        onOpenSettings = { showSettings = true },
                        onDismissNotice = { viewModel.dismissNotice() }
                    )
                }
            }
        }
    }

    private fun onToggleConnection() {
        if (viewModel.isConnectionActive()) {
            viewModel.disconnect()
            return
        }
        if (!viewModel.prepareToConnect()) return

        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnPermissionAndStart()
        }
    }

    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            DnsVpnService.start(this)
        }
    }
}
