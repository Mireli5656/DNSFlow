package com.dnsflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dnsflow.app.config.DnsProtocol
import com.dnsflow.app.config.DnsProviders
import com.dnsflow.app.ui.components.InfoCard
import com.dnsflow.app.ui.components.NoticeBanner
import com.dnsflow.app.ui.components.ProtocolSelector

private val privacyPoints = listOf(
    "DNSFlow does not log or store your DNS queries.",
    "No analytics, no ads, no accounts.",
    "Queries are sent only to the resolver you choose, over an encrypted DoH or DoT connection with full certificate validation.",
    "Only DNS traffic goes through the local VPN. Your other traffic does not pass through it.",
    "Your settings are stored only on this device.",
    "The resolver you choose can see your queries and is covered by its own privacy policy."
)

@Composable
fun SettingsScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onProtocolSelected: (DnsProtocol) -> Unit,
    onIpv4Changed: (Boolean) -> Unit,
    onIpv6Changed: (Boolean) -> Unit,
    onSaveCustom: (dohUrl: String, dotHost: String, dotPort: String, bootstrapIp: String) -> String?,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val colors = MaterialTheme.colorScheme
    val versionName = rememberVersionName()
    val packageName = LocalContext.current.packageName

    var customSelected by rememberSaveable { mutableStateOf(config.isCustom) }
    var dohUrl by rememberSaveable { mutableStateOf(config.customDohUrl) }
    var dotHost by rememberSaveable { mutableStateOf(config.customDotHost) }
    var dotPort by rememberSaveable { mutableStateOf(config.customDotPort.toString()) }
    var bootstrapIp by rememberSaveable { mutableStateOf(config.customBootstrapIp) }
    var customError by rememberSaveable { mutableStateOf<String?>(null) }
    var customSaved by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onBackground
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground
            )
        }

        state.notice?.let {
            NoticeBanner(message = it, onDismiss = onDismissNotice)
        }

        SectionTitle("DNS provider")
        InfoCard(modifier = Modifier.fillMaxWidth()) {
            DnsProviders.all.forEachIndexed { index, provider ->
                if (index > 0) HorizontalDivider(color = colors.outline)
                ProviderRow(
                    name = provider.name,
                    description = provider.description,
                    selected = !customSelected && config.providerId == provider.id,
                    onClick = {
                        customSelected = false
                        onSelectProvider(provider.id)
                    }
                )
            }
            HorizontalDivider(color = colors.outline)
            ProviderRow(
                name = "Custom server",
                description = "Use your own DoH or DoT resolver",
                selected = customSelected,
                onClick = { customSelected = true }
            )
        }

        if (customSelected) {
            SectionTitle("Custom server")
            InfoCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Fill in the endpoint for the protocol you want to use. " +
                        "The server IP is optional and saves a lookup of the server name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingsTextField(
                    value = dohUrl,
                    onValueChange = {
                        dohUrl = it
                        customSaved = false
                    },
                    label = "DoH URL",
                    hint = "https://dns.example.com/dns-query",
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(
                    value = dotHost,
                    onValueChange = {
                        dotHost = it
                        customSaved = false
                    },
                    label = "DoT host",
                    hint = "dns.example.com",
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(
                    value = dotPort,
                    onValueChange = {
                        dotPort = it
                        customSaved = false
                    },
                    label = "DoT port",
                    hint = "853",
                    keyboardType = KeyboardType.Number
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsTextField(
                    value = bootstrapIp,
                    onValueChange = {
                        bootstrapIp = it
                        customSaved = false
                    },
                    label = "Server IP (optional)",
                    hint = "203.0.113.10",
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val error = onSaveCustom(dohUrl, dotHost, dotPort, bootstrapIp)
                        customError = error
                        customSaved = error == null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = "Save and use this server")
                }
                customError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error
                    )
                }
                if (customSaved) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Custom server saved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary
                    )
                }
            }
        }

        SectionTitle("Protocol")
        InfoCard(modifier = Modifier.fillMaxWidth()) {
            ProtocolSelector(
                selected = config.protocol,
                onSelected = onProtocolSelected
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "DoH sends queries over HTTPS (port 443). " +
                    "DoT uses a dedicated TLS connection (port 853).",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }

        SectionTitle("IP versions")
        InfoCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "IPv4",
                subtitle = "Handle DNS queries sent over IPv4",
                checked = config.ipv4Enabled,
                onCheckedChange = onIpv4Changed
            )
            HorizontalDivider(color = colors.outline)
            SwitchRow(
                title = "IPv6",
                subtitle = "Handle DNS queries sent over IPv6",
                checked = config.ipv6Enabled,
                onCheckedChange = onIpv6Changed
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "At least one must stay enabled.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }

        SectionTitle("Privacy")
        InfoCard(modifier = Modifier.fillMaxWidth()) {
            privacyPoints.forEachIndexed { index, point ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = point,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                }
            }
        }

        SectionTitle("About")
        InfoCard(modifier = Modifier.fillMaxWidth()) {
            InfoRow(label = "App", value = "DNSFlow")
            InfoRow(label = "Version", value = versionName)
            InfoRow(label = "Package", value = packageName)
            InfoRow(label = "Protocols", value = "DoH and DoT")
            InfoRow(label = "License", value = "MIT")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun ProviderRow(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.onSurfaceVariant
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.surfaceVariant,
                uncheckedBorderColor = colors.outline
            )
        )
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    keyboardType: KeyboardType
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = label) },
        placeholder = { Text(text = hint) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next
        )
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
