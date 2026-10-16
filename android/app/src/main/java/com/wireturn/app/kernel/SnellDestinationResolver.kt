package com.wireturn.app.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.SharedNetworkSettings

/** The same DNS path for Snell VPN traffic and standalone profile checks. */
object SnellDestinationResolver {
    const val ENVIRONMENT_KEY = "TUNA_RESOLVE_SOCKS_DESTINATION"

    fun required(config: KernelConfig): Boolean =
        config is KernelConfig.Vpn && config.config.protocol == "snell"

    /** HEV MAPDNS must resolve restored names through the client's DNS for every
     * protocol, including native VLESS, rather than delegating DNS to the server.
     * Resolve a restored FakeDNS domain before the local SOCKS UDP hop. Otherwise
     * the upstream may answer from a real IP that cannot be mapped to the fake
     * address expected by the Android socket. Keep standalone probes and profiles
     * without FakeDNS on their existing resolver path.
     */
    fun forTunnel(config: KernelConfig, fakeDnsEnabled: Boolean, hevEnabled: Boolean = false): Boolean =
        required(config) || hevEnabled || (fakeDnsEnabled && config is KernelConfig.Vpn &&
            config.config.protocol in setOf("awg", "wireguard", "trusttunnel"))

    fun upstream(config: ClientConfig): String {
        val address = config.socksAddr.replace("0.0.0.0:", "127.0.0.1:")
        return if (config.isSocksAuthEnabled && config.socksUser.isNotBlank())
            "${config.socksUser}:${config.socksPass}@$address" else address
    }

    fun probePolicy(settings: SharedNetworkSettings): String {
        val compiled = XrayNetworkCompiler.compile(settings, hevEnabled = true)
        return JsonParser.parseString(compiled.policyJson).asJsonObject.apply {
            // A profile check must measure that profile, even if a user's rule
            // would send the probe site DIRECT. Keep its chosen remote DNS,
            // static hosts, family strategy and cache policy.
            addProperty("mode", "ALL_VPN")
            add("rules", JsonArray())
        }.toString()
    }
}
