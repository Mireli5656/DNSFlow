package com.dnsflow.app.dns

import com.dnsflow.app.config.DnsConfig
import com.dnsflow.app.config.DnsProtocol
import com.dnsflow.app.config.ResolverEndpoint

object ResolverFactory {

    /** Builds the resolver for [config]. Throws [IllegalArgumentException] if it is invalid. */
    fun create(config: DnsConfig, network: UpstreamNetwork): DnsResolver {
        val endpoint = config.resolverEndpoint()
            ?: throw IllegalArgumentException(config.validate() ?: "Invalid DNS configuration")
        return create(endpoint, network)
    }

    fun create(endpoint: ResolverEndpoint, network: UpstreamNetwork): DnsResolver =
        when (endpoint.protocol) {
            DnsProtocol.DOH -> DohResolver(endpoint, network)
            DnsProtocol.DOT -> DotResolver(endpoint, network)
        }
}
