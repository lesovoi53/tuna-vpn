package com.wireturn.app.domain

import com.wireturn.app.data.Profile
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.kernel.VpnConfig
import java.net.URI
import java.util.UUID

/** Resolves local continuity within one subscription, without changing incoming source IDs. */
object SubscriptionIdentity {
    private fun credentialKey(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val gson = com.google.gson.GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun sourceConfig(profile: Profile): KernelConfig = profile.subscriptionKernelBaseline?.let {
        runCatching { gson.fromJson(it, KernelConfig::class.java) }.getOrNull()
    } ?: profile.kernelConfig

    private fun isText(profile: Profile): Boolean {
        if (profile.subscriptionUriIdentity) return true
        // Recognise old text imports only when the stored source ID is demonstrably URI-derived.
        // A server-provided UUID is otherwise authoritative even when an endpoint/name matches.
        val uri = (sourceConfig(profile) as? KernelConfig.Vpn)?.config?.uri ?: return false
        return profile.subscriptionSourceId == UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun endpoint(profile: Profile): String? = runCatching {
        when (val k = sourceConfig(profile)) {
            is KernelConfig.Vpn -> {
                if (k.config.protocol in setOf("awg", "wireguard")) return@runCatching "awg|" + com.wireturn.app.data.kernel.AwgUri.accountIdentity(k.config.uri)
                val u = URI(k.config.uri)
                require(!u.host.isNullOrBlank())
                val port = if (k.config.protocol == "mieru") u.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.substringBefore('=') == "port" }?.substringAfter('=')?.let(VpnConfig::decode)
                    else u.port.toString()
                require(!port.isNullOrBlank())
                "vpn|${k.config.protocol}|${u.host.lowercase()}|$port|${credentialKey(VpnConfig.decode(u.rawUserInfo.orEmpty()))}"
            }
            is KernelConfig.Qwdtt -> "qwdtt|${k.config.peer.lowercase()}|${credentialKey(k.config.password)}"
            is KernelConfig.Webdav -> URI(k.config.webdav).let {
                "webdav|${it.scheme}|${it.host?.lowercase()}|${it.port}|${it.rawPath}"
            }
            is KernelConfig.MasterDns -> "dns|${k.config.domains.map(String::lowercase).sorted()}"
            else -> null
        }
    }.getOrNull()

    /** Precompute once for a refresh; matching never depends on order within a duplicate endpoint. */
    class Index(batch: List<Profile>, existing: List<Profile>) {
        private val sources = existing.groupBy { it.subscriptionSourceId }
        private val incomingKeys = batch.filter { it.subscriptionUriIdentity }.groupBy(::endpoint)
        private val previousKeys = existing.filter(::isText).groupBy(::endpoint)
        private val incomingIds = batch.map { it.id }.toSet()
        fun match(incoming: Profile, used: Set<String>): Profile? {
            sources[incoming.id]?.singleOrNull()?.takeIf { it.id !in used }?.let { return it }
            if (!incoming.subscriptionUriIdentity) return null
            val key = endpoint(incoming) ?: return null
            if (incomingKeys[key]?.size != 1) return null
            return previousKeys[key]?.singleOrNull()?.takeIf {
                it.id !in used && it.subscriptionSourceId !in incomingIds
            }
        }
    }

    fun match(incoming: Profile, batch: List<Profile>, existing: List<Profile>, used: Set<String>): Profile? =
        Index(batch, existing).match(incoming, used)
}
