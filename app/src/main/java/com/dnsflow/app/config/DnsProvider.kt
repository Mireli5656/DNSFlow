package com.dnsflow.app.config

/**
 * A built-in DNS resolver.
 *
 * [bootstrapIps] are the resolver's own addresses (IPv4 first, then IPv6). They are used to
 * reach the resolver without a plain-text lookup of its host name. TLS certificates are still
 * verified against the host name, never against the IP.
 */
data class DnsProvider(
    val id: String,
    val name: String,
    val description: String,
    val dohUrl: String,
    val dotHost: String,
    val dotPort: Int = DEFAULT_DOT_PORT,
    val bootstrapIps: List<String>
)
