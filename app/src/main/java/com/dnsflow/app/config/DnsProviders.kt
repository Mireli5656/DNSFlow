package com.dnsflow.app.config

object DnsProviders {

    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "cloudflare"

    val all: List<DnsProvider> = listOf(
        DnsProvider(
            id = "cloudflare",
            name = "Cloudflare",
            description = "Privacy-focused public resolver, no filtering",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            dotHost = "one.one.one.one",
            bootstrapIps = listOf(
                "1.1.1.1",
                "1.0.0.1",
                "2606:4700:4700::1111",
                "2606:4700:4700::1001"
            )
        ),
        DnsProvider(
            id = "google",
            name = "Google Public DNS",
            description = "Google's public resolver, no filtering",
            dohUrl = "https://dns.google/dns-query",
            dotHost = "dns.google",
            bootstrapIps = listOf(
                "8.8.8.8",
                "8.8.4.4",
                "2001:4860:4860::8888",
                "2001:4860:4860::8844"
            )
        ),
        DnsProvider(
            id = "quad9",
            name = "Quad9",
            description = "Blocks known malicious domains",
            dohUrl = "https://dns.quad9.net/dns-query",
            dotHost = "dns.quad9.net",
            bootstrapIps = listOf(
                "9.9.9.9",
                "149.112.112.112",
                "2620:fe::fe",
                "2620:fe::9"
            )
        ),
        DnsProvider(
            id = "nextdns",
            name = "NextDNS",
            description = "Public endpoint without a profile, no filtering",
            dohUrl = "https://dns.nextdns.io/",
            dotHost = "dns.nextdns.io",
            bootstrapIps = listOf(
                "45.90.28.0",
                "45.90.30.0"
            )
        ),
        DnsProvider(
            id = "adguard",
            name = "AdGuard DNS",
            description = "Blocks ads, trackers and malware",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            dotHost = "dns.adguard-dns.com",
            bootstrapIps = listOf(
                "94.140.14.14",
                "94.140.15.15",
                "2a10:50c0::ad1:ff",
                "2a10:50c0::ad2:ff"
            )
        ),
        DnsProvider(
            id = "controld",
            name = "Control D",
            description = "Free resolver that blocks malware and ads",
            dohUrl = "https://freedns.controld.com/p2",
            dotHost = "p2.freedns.controld.com",
            bootstrapIps = listOf(
                "76.76.2.2",
                "76.76.10.2",
                "2606:1a40::2",
                "2606:1a40:1::2"
            )
        )
    )

    fun byId(id: String): DnsProvider? = all.firstOrNull { it.id == id }
}
