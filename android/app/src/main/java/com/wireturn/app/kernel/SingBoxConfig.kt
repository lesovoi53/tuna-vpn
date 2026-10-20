package com.wireturn.app.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wireturn.app.data.kernel.VpnConfig
import java.net.URI

object SingBoxConfig {
    /** Preserve imported settings; the Android runtime defaults to ordinary TCP for Snell.
     * TFO stalled post-handshake data on HONOR in repeated on/off comparisons (rc20).
     * Apply after native overrides so probes, previews and the tunnel use the same policy.
     */
    fun outbound(profile: VpnConfig): JsonObject = profile.outbound().apply {
        if (profile.protocol == "snell" && profile.options?.allowExperimentalSnellTfo != true) {
            addProperty("tcp_fast_open", false)
        }
    }

    fun snellTfoSuppressed(profile: VpnConfig): Boolean =
        profile.protocol == "snell" && profile.options?.allowExperimentalSnellTfo != true &&
            runCatching { profile.outbound().get("tcp_fast_open")?.asBoolean == true }.getOrDefault(false)

    fun generate(profile: VpnConfig, listenAddress: String, username: String = "", password: String = "", allowNoAuth: Boolean = false, bootstrapDns: String = ""): JsonObject {
        require(allowNoAuth || (username.isNotBlank() && password.isNotBlank()))
        val address = URI("socks://$listenAddress")
        require(address.host in setOf("127.0.0.1", "[::1]", "::1") && address.port in 1..65535)
        val wireguard = profile.protocol in setOf("awg", "wireguard")
        val trustIpv4Only = profile.protocol == "trusttunnel" &&
            com.wireturn.app.data.kernel.TrustTunnelUri.ipv4Only(profile.uri)
        val wg = if (wireguard) com.wireturn.app.data.kernel.AwgUri.parse(profile.uri) else null
        val profileDns = wg?.dns.orEmpty()
        val innerAddresses = wg?.endpoint?.getAsJsonArray("address")?.map { it.asString }.orEmpty()
        val payloadStrategy = when {
            trustIpv4Only -> "ipv4_only"
            innerAddresses.isEmpty() -> null
            innerAddresses.none { ':' in it } -> "ipv4_only"
            innerAddresses.all { ':' in it } -> "ipv6_only"
            else -> null
        }
        return JsonObject().apply {
            add("log", JsonObject().apply { addProperty("level", "info"); addProperty("timestamp", false) })
            add("dns", JsonObject().apply {
                payloadStrategy?.let { addProperty("strategy", it) }
                if (profileDns.isNotEmpty()) addProperty("final", "profile-dns-0")
                add("servers", JsonArray().apply {
                    profileDns.forEachIndexed { index, ip -> add(JsonObject().apply {
                        // DNS over TCP survives lost datagrams during a WG handshake.
                        // Keep the imported resolver and send queries inside the tunnel.
                        addProperty("type", "tcp"); addProperty("tag", "profile-dns-$index")
                        addProperty("server", ip); addProperty("server_port", 53); addProperty("detour", "vpn")
                    }) }
                    // Standalone Android binaries cannot use desktop resolv.conf:
                    // it falls back to ::1:53, where Android has no DNS listener.
                    add(com.wireturn.app.data.BootstrapDns.parse(bootstrapDns)?.singBoxServer() ?: JsonObject().apply {
                        addProperty("type", "tcp"); addProperty("tag", "bootstrap")
                        addProperty("server", "1.1.1.1"); addProperty("server_port", 53)
                    })
                })
            })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "socks"); addProperty("tag", "local")
                    addProperty("listen", address.host.removeSurrounding("[", "]")); addProperty("listen_port", address.port)
                    if (!allowNoAuth) add("users", JsonArray().apply {
                        add(JsonObject().apply { addProperty("username", username); addProperty("password", password) })
                    })
                })
            })
            add(if (wireguard) "endpoints" else "outbounds", JsonArray().apply { add(outbound(profile)) })
            add("route", JsonObject().apply {
                addProperty("final", "vpn")
                if (trustIpv4Only) add("rules", JsonArray().apply {
                    // Resolve payload domains to A records. IPv6 literals must fail closed,
                    // never fall back to a direct outbound or the physical network.
                    add(JsonObject().apply {
                        addProperty("action", "resolve")
                        addProperty("strategy", "ipv4_only")
                        addProperty("server", "bootstrap")
                    })
                    add(JsonObject().apply {
                        addProperty("ip_version", 6)
                        addProperty("action", "reject")
                    })
                })
                if (payloadStrategy != null) add("default_domain_resolver", JsonObject().apply {
                    addProperty("server", "bootstrap")
                    // The physical peer may use IPv6 even with an IPv4-only inner tunnel.
                    addProperty("strategy", "prefer_ipv4")
                }) else addProperty("default_domain_resolver", "bootstrap")
            })
        }
    }
}
