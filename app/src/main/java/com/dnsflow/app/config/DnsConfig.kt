package com.dnsflow.app.config

import java.net.URI

const val DEFAULT_DOT_PORT = 853
private const val DEFAULT_DOH_PORT = 443

enum class DnsProtocol(val label: String) {
    DOH("DoH"),
    DOT("DoT")
}

/**
 * A concrete upstream resolver, derived from a [DnsConfig].
 *
 * [host] is the name used for TLS server name indication and certificate verification.
 * [dohUrl] is set only for DoH. [bootstrapIps] may be empty; the engines then look the host up
 * on the underlying (non-VPN) network.
 */
data class ResolverEndpoint(
    val protocol: DnsProtocol,
    val dohUrl: String?,
    val host: String,
    val port: Int,
    val bootstrapIps: List<String>
)

data class DnsConfig(
    val providerId: String = DnsProviders.DEFAULT_ID,
    val protocol: DnsProtocol = DnsProtocol.DOH,
    val customDohUrl: String = "",
    val customDotHost: String = "",
    val customDotPort: Int = DEFAULT_DOT_PORT,
    val customBootstrapIp: String = "",
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = true
) {

    val isCustom: Boolean
        get() = providerId == DnsProviders.CUSTOM_ID

    val provider: DnsProvider?
        get() = DnsProviders.byId(providerId)

    val providerName: String
        get() = if (isCustom) "Custom server" else provider?.name ?: "Unknown provider"

    val endpointLabel: String
        get() = when (protocol) {
            DnsProtocol.DOH -> {
                if (isCustom) {
                    customDohUrl.trim().ifEmpty { "Not set" }
                } else {
                    provider?.dohUrl ?: "Unknown"
                }
            }
            DnsProtocol.DOT -> {
                if (isCustom) {
                    val host = customDotHost.trim()
                    if (host.isEmpty()) "Not set" else "$host:$customDotPort"
                } else {
                    val preset = provider
                    if (preset == null) "Unknown" else "${preset.dotHost}:${preset.dotPort}"
                }
            }
        }

    /** Returns a user-facing error message, or null when this config can be used. */
    fun validate(): String? {
        if (!ipv4Enabled && !ipv6Enabled) {
            return "At least one IP version must stay enabled."
        }

        if (!isCustom) {
            return if (provider == null) "The selected DNS provider is not available." else null
        }

        val dohUrl = customDohUrl.trim()
        val dotHost = customDotHost.trim()
        val bootstrap = customBootstrapIp.trim()

        if (dohUrl.isNotEmpty() && parseDohUri(dohUrl) == null) {
            return "The DoH URL must start with https:// and contain a valid host."
        }
        if (dotHost.isNotEmpty() && !isValidHost(dotHost)) {
            return "The DoT host is not a valid host name or IP address."
        }
        if (customDotPort !in 1..65535) {
            return "The DoT port must be between 1 and 65535."
        }
        if (bootstrap.isNotEmpty() && !isIpLiteral(bootstrap)) {
            return "The server IP must be a valid IPv4 or IPv6 address."
        }

        return when (protocol) {
            DnsProtocol.DOH ->
                if (dohUrl.isEmpty()) "DoH is selected, so a DoH URL is required." else null
            DnsProtocol.DOT ->
                if (dotHost.isEmpty()) "DoT is selected, so a DoT host is required." else null
        }
    }

    /** The resolver to use for the selected protocol, or null if the config is invalid. */
    fun resolverEndpoint(): ResolverEndpoint? {
        if (validate() != null) return null

        if (isCustom) {
            val bootstrap: List<String> =
                if (customBootstrapIp.isBlank()) emptyList() else listOf(customBootstrapIp.trim())
            return when (protocol) {
                DnsProtocol.DOH -> dohEndpoint(customDohUrl.trim(), bootstrap)
                DnsProtocol.DOT -> ResolverEndpoint(
                    protocol = DnsProtocol.DOT,
                    dohUrl = null,
                    host = stripBrackets(customDotHost.trim()),
                    port = customDotPort,
                    bootstrapIps = bootstrap
                )
            }
        }

        val preset = provider ?: return null
        return when (protocol) {
            DnsProtocol.DOH -> dohEndpoint(preset.dohUrl, preset.bootstrapIps)
            DnsProtocol.DOT -> ResolverEndpoint(
                protocol = DnsProtocol.DOT,
                dohUrl = null,
                host = preset.dotHost,
                port = preset.dotPort,
                bootstrapIps = preset.bootstrapIps
            )
        }
    }
}

private val hostnameRegex = Regex(
    "^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
        "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
)
private val digitsAndDotsRegex = Regex("^[0-9.]+$")

private fun dohEndpoint(url: String, bootstrap: List<String>): ResolverEndpoint? {
    val uri = parseDohUri(url) ?: return null
    val port = if (uri.port == -1) DEFAULT_DOH_PORT else uri.port
    return ResolverEndpoint(
        protocol = DnsProtocol.DOH,
        dohUrl = uri.toString(),
        host = stripBrackets(uri.host),
        port = port,
        bootstrapIps = bootstrap
    )
}

private fun parseDohUri(url: String): URI? {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        return null
    }
    if (uri.scheme?.lowercase() != "https") return null
    if (uri.userInfo != null) return null
    val host = uri.host ?: return null
    if (!isValidHost(host)) return null
    return uri
}

private fun stripBrackets(value: String): String = value.removePrefix("[").removeSuffix("]")

private fun isValidHost(value: String): Boolean {
    val host = stripBrackets(value)
    if (host.isEmpty()) return false
    if (isIpv4Literal(host) || isIpv6Literal(host)) return true
    if (digitsAndDotsRegex.matches(host)) return false
    return hostnameRegex.matches(host)
}

private fun isIpLiteral(value: String): Boolean = isIpv4Literal(value) || isIpv6Literal(value)

private fun isIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all { it in '0'..'9' } &&
            part.toInt() in 0..255
    }
}

private fun isIpv6Literal(raw: String): Boolean {
    if (!raw.contains(':')) return false

    // An embedded IPv4 tail (for example ::ffff:1.2.3.4) counts as two groups.
    var value = raw
    val lastColon = value.lastIndexOf(':')
    val tail = value.substring(lastColon + 1)
    if (tail.contains('.')) {
        if (!isIpv4Literal(tail)) return false
        value = value.substring(0, lastColon + 1) + "0:0"
    }

    val compressedAt = value.indexOf("::")
    if (compressedAt != value.lastIndexOf("::")) return false
    val hasCompression = compressedAt >= 0

    val head = if (hasCompression) value.substring(0, compressedAt) else value
    val rest = if (hasCompression) value.substring(compressedAt + 2) else ""
    val groups = (if (head.isEmpty()) emptyList() else head.split(':')) +
        (if (rest.isEmpty()) emptyList() else rest.split(':'))

    val groupsAreValid = groups.all { group ->
        group.length in 1..4 && group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
    if (!groupsAreValid) return false

    return if (hasCompression) groups.size <= 7 else groups.size == 8
}
