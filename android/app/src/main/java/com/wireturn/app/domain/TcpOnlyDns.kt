package com.wireturn.app.domain

import com.google.gson.JsonParser
import com.wireturn.app.data.*

/** Runtime-only adaptation: same resolver and route, using TCP through TCP-only tunnels. */
object TcpOnlyDns {
    fun forProfile(settings: SharedNetworkSettings, config: KernelConfig?): SharedNetworkSettings {
        val needed = (config is KernelConfig.Vpn && config.config.protocol == "naive" &&
                !com.wireturn.app.data.kernel.NaiveUdpOverTcp.enabled(config.config))
        if (!needed) return settings
        val resolved = NetworkPolicyResolver.resolveForCompilation(settings)
        val inherited = resolved.subscription?.let { SplitDnsSettings.fromPolicy(it).toJson() }
            ?: JsonParser.parseString(resolved.routing.toJson()).asJsonObject["splitDns"]?.toString()
        val split = when (settings.dns.overrideMode) {
            SplitDnsOverride.CUSTOM -> SplitDnsSettings.parse(settings.dns.splitDnsJson)
            SplitDnsOverride.DISABLED -> null
            SplitDnsOverride.INHERIT -> inherited?.let(SplitDnsSettings::parse)
        }
        if (split != null) {
            if (split.effectiveRemoteServers.none { it.transport == "UDP" } &&
                split.remoteEmergency?.resolver?.transport != "UDP") return settings
            fun adapt(resolver: SubscriptionPolicyParser.Resolver) =
                if (resolver.transport == "UDP") resolver.copy(transport = "TCP") else resolver
            return settings.copy(dns = settings.dns.copy(splitDnsJson =
                split.copy(remote = adapt(split.remote), remoteServers = split.remoteServers?.map(::adapt),
                    remoteEmergency = split.remoteEmergency?.let { recovery ->
                        recovery.copy(resolver = recovery.resolver?.let(::adapt))
                    }).toJson()))
        }
        val servers = settings.dns.servers.split(',', '\n').map(String::trim).filter(String::isNotEmpty).joinToString(",") {
            when {
                it.startsWith("udp://") -> "tcp://" + it.removePrefix("udp://")
                it.contains("://") -> it
                else -> "tcp://$it"
            }
        }
        return settings.copy(dns = settings.dns.copy(servers = servers))
    }
}
