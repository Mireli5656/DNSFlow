package com.dnsflow.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DnsFlowColorScheme = darkColorScheme(
    primary = DnsGreen,
    onPrimary = DnsBackground,
    primaryContainer = DnsSurfaceVariant,
    onPrimaryContainer = DnsGreen,
    secondary = DnsGreen,
    onSecondary = DnsBackground,
    background = DnsBackground,
    onBackground = DnsOnBackground,
    surface = DnsSurface,
    onSurface = DnsOnBackground,
    surfaceVariant = DnsSurfaceVariant,
    onSurfaceVariant = DnsMuted,
    outline = DnsOutline,
    outlineVariant = DnsOutline,
    error = DnsError,
    onError = DnsBackground
)

@Composable
fun DnsFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DnsFlowColorScheme,
        typography = DnsTypography,
        content = content
    )
}
