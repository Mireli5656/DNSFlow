package com.dnsflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dnsflow.app.config.DnsConfig
import com.dnsflow.app.config.DnsProtocol
import com.dnsflow.app.state.ConnectionState
import com.dnsflow.app.ui.components.ConnectButton
import com.dnsflow.app.ui.components.InfoCard
import com.dnsflow.app.ui.components.NoticeBanner
import com.dnsflow.app.ui.components.ProtocolSelector
import com.dnsflow.app.ui.theme.DnsWarning

@Composable
fun MainScreen(
    state: MainUiState,
    onToggleConnection: () -> Unit,
    onProtocolSelected: (DnsProtocol) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Header(onOpenSettings = onOpenSettings)

        Spacer(modifier = Modifier.height(24.dp))
        StatusSection(connection = state.connection, config = config)

        Spacer(modifier = Modifier.height(28.dp))
        ConnectButton(state = state.connection, onClick = onToggleConnection)

        Spacer(modifier = Modifier.height(32.dp))

        state.notice?.let {
            NoticeBanner(message = it, onDismiss = onDismissNotice)
            Spacer(modifier = Modifier.height(12.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenSettings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        CardLabel("DNS provider")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = config.providerName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant
                    )
                }
            }

            InfoCard(modifier = Modifier.fillMaxWidth()) {
                CardLabel("Protocol")
                Spacer(modifier = Modifier.height(10.dp))
                ProtocolSelector(
                    selected = config.protocol,
                    onSelected = onProtocolSelected
                )
            }

            InfoCard(modifier = Modifier.fillMaxWidth()) {
                CardLabel("Current endpoint")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = config.endpointLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    val green = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                append("DNS")
                withStyle(SpanStyle(color = green)) {
                    append("Flow")
                }
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusSection(connection: ConnectionState, config: DnsConfig) {
    val colors = MaterialTheme.colorScheme

    val (title, subtitle, accent) = when (connection) {
        is ConnectionState.Connected -> Triple(
            "Connected",
            "DNS queries go to ${config.providerName} over ${config.protocol.label}",
            colors.primary
        )
        is ConnectionState.Connecting -> Triple(
            "Connecting…",
            "Setting up the DNS tunnel",
            DnsWarning
        )
        is ConnectionState.Disconnected -> Triple(
            "Disconnected",
            "Your device uses its default DNS",
            colors.onSurfaceVariant
        )
        is ConnectionState.Error -> Triple(
            "Connection error",
            connection.message,
            colors.error
        )
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (connection is ConnectionState.Error) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
