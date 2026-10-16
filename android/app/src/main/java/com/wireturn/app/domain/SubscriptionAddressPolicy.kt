package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import java.net.URI

/** Local address preference is never taken from the subscription payload. */
internal object SubscriptionAddressPolicy {
    fun canKeep(remote: Profile, existing: Profile?): Boolean {
        if (existing?.keepSubscriptionAddress != true) return false
        val old = (existing.kernelConfig as? KernelConfig.Vpn)?.config ?: return false
        val next = (remote.kernelConfig as? KernelConfig.Vpn)?.config ?: return false
        return old.protocol == next.protocol && runCatching {
            !URI(old.uri).host.isNullOrBlank() && !URI(next.uri).host.isNullOrBlank()
        }.getOrDefault(false)
    }

    fun preserve(remote: Profile, existing: Profile): KernelConfig {
        val next = (remote.kernelConfig as KernelConfig.Vpn).config
        val old = (existing.kernelConfig as KernelConfig.Vpn).config
        val address = URI(old.uri).host.removeSurrounding("[", "]")
        val uri = URI(next.uri)
        if (uri.host.removeSurrounding("[", "]") == address) return remote.kernelConfig
        // CF profiles need their implicit TLS/HTTP names resolved from the NEW subscription
        // address before substituting the dial IP. Do not borrow old TLS settings.
        if (CfAddress.literal(address) != null) {
            val plan = runCatching { CloudflareProfile.plan(remote) }.getOrNull()
            if (plan != null) return plan.profileFor(address).kernelConfig
        }
        // Mieru permits repeated port/protocol entries; do not run a single-value TLS
        // query parser for protocols which have no TLS-name field.
        val query = if (next.protocol in setOf("vless", "trojan", "hysteria2", "tuic", "anytls", "naive"))
            CloudflareProfile.query(uri) else emptyMap()
        val usesTls = next.protocol in setOf("trojan", "hysteria2", "tuic", "anytls", "naive") ||
            (next.protocol == "vless" && query["security"] in setOf("tls", "reality"))
        val explicitQuery = if (usesTls && (query["sni"] ?: query["serverName"]).isNullOrBlank()) {
            // Empty names also mean "use the dial host" to the core. Materialize the
            // NEW subscription's hostname before pinning, and do not append a duplicate key.
            val remaining = uri.rawQuery?.split('&')?.filterNot {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") in setOf("sni", "serverName")
            }?.joinToString("&")?.takeIf { it.isNotEmpty() }
            listOfNotNull(remaining,
                "sni=${CloudflareProfile.enc(uri.host.removeSurrounding("[", "]"))}").joinToString("&")
        } else uri.rawQuery
        val host = if (':' in address) "[$address]" else address
        val replacement = "${uri.scheme}://" + (uri.rawUserInfo?.let { "$it@" } ?: "") + host +
            (if (uri.port >= 0) ":${uri.port}" else "") + uri.rawPath.orEmpty() +
            (explicitQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        return KernelConfig.Vpn(next.copy(uri = replacement))
    }
}
