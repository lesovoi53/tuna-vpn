package com.wireturn.app.domain

import com.google.gson.*
import com.wireturn.app.data.*

/** Three-way subscription refresh: server changes apply only where the user did not override them. */
object SubscriptionProfileMerge {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    fun snapshot(kernel: KernelConfig): JsonObject = gson.toJsonTree(kernel, KernelConfig::class.java).asJsonObject
    fun normalizedSnapshot(baseline: JsonObject): JsonObject = snapshot(gson.fromJson(baseline, KernelConfig::class.java))
    fun auxiliarySnapshot(profile: Profile): JsonObject = JsonObject().apply {
        val serialized = gson.toJsonTree(profile).asJsonObject
        listOf("xrayEnabled", "xrayProtocol", "wgConfig", "vlessConfig").forEach { add(it, serialized[it]?.deepCopy()) }
    }
    fun auxiliary(existing: Profile?, incoming: Profile): Profile {
        if (existing == null) return incoming
        val baseline = existing.subscriptionAuxBaseline
        if (baseline == null) return incoming.copy(xrayEnabled = existing.xrayEnabled,
            xrayProtocol = existing.xrayProtocol, wgConfig = existing.wgConfig, vlessConfig = existing.vlessConfig)
        val fields = mergeJson(baseline, auxiliarySnapshot(existing), auxiliarySnapshot(incoming))!!.asJsonObject
        val serialized = gson.toJsonTree(incoming).asJsonObject
        fields.entrySet().forEach { (key, value) -> serialized.add(key, value) }
        return gson.fromJson(serialized, Profile::class.java)
    }
    fun mergeJson(base: JsonElement?, local: JsonElement?, remote: JsonElement?): JsonElement? {
        if (local == base) return remote?.deepCopy()
        if (remote == base || local == remote) return local?.deepCopy()
        if (local?.isJsonObject == true && remote?.isJsonObject == true) {
            val b = base?.takeIf { it.isJsonObject }?.asJsonObject
            return JsonObject().apply {
                (local.asJsonObject.keySet() + remote.asJsonObject.keySet() + b?.keySet().orEmpty()).forEach { key ->
                    mergeJson(b?.get(key), local.asJsonObject[key], remote.asJsonObject[key])?.let { add(key, it) }
                }
            }
        }
        return local?.deepCopy() // Conflicting edits keep the user's value, including deletion.
    }
    fun legacyOptions(local: com.wireturn.app.data.kernel.VpnOptions?, remote: com.wireturn.app.data.kernel.VpnOptions?): com.wireturn.app.data.kernel.VpnOptions? {
        if (local == null) return remote
        if (remote == null) return local
        require(local.version == remote.version) { "Несовместимые версии дополнительных параметров" }
        fun combine(old: JsonObject?, next: JsonObject?) = if (old == null && next == null) null else
            com.wireturn.app.data.kernel.NativeOptions.merge(next ?: JsonObject(), old ?: JsonObject())
        return local.copy(singBox = combine(local.singBox, remote.singBox), xray = combine(local.xray, remote.xray),
            fragmentation = local.fragmentation ?: remote.fragmentation)
    }
    fun kernel(existing: Profile, incoming: Profile): KernelConfig {
        val source = when (val kernel = incoming.kernelConfig) {
            is KernelConfig.MasterDns -> incoming.copy(kernelConfig = KernelConfig.MasterDns(
                kernel.config.copy(localPool = null)))
            else -> incoming
        }
        return mergeKernel(existing, source)
    }

    private fun mergeKernel(existing: Profile, incoming: Profile): KernelConfig {
        require(existing.category == incoming.category) { "Подписка изменила категорию профиля; обновление отменено" }
        val local = snapshot(existing.kernelConfig)
        val remote = snapshot(incoming.kernelConfig)
        if (local["type"] != remote["type"]) return incoming.kernelConfig
        val baseline = existing.subscriptionKernelBaseline
        if (baseline != null && baseline["type"] == remote["type"]) {
            // URIs contain both server-owned credentials/address and independently edited
            // client transport parameters. Merge their query fields, not the opaque string.
            if (existing.kernelConfig is KernelConfig.Vpn && incoming.kernelConfig is KernelConfig.Vpn) {
                val baseConfig = (gson.fromJson(baseline, KernelConfig::class.java) as KernelConfig.Vpn).config
                val old = existing.kernelConfig.config
                val next = incoming.kernelConfig.config
                if (baseConfig.protocol != next.protocol || old.protocol != next.protocol) return incoming.kernelConfig
                if (old.isRawXray && next.isRawXray) {
                    // Merge source JSON structurally instead of interpreting it as a VLESS URI.
                    val beforeRaw = JsonParser.parseString(requireNotNull(baseConfig.rawXrayJson))
                    val localRaw = JsonParser.parseString(requireNotNull(old.rawXrayJson))
                    val remoteRaw = JsonParser.parseString(requireNotNull(next.rawXrayJson))
                    val mergedRaw = mergeJson(beforeRaw, localRaw, remoteRaw)!!.toString()
                    val merged = (gson.fromJson<KernelConfig>(mergeJson(baseline, local, remote), KernelConfig::class.java) as KernelConfig.Vpn).config
                    val config = merged.copy(rawXrayJson = mergedRaw).sanitize()
                    require(config.isValid()) { "Новые параметры Xray JSON несовместимы с локальными настройками" }
                    return KernelConfig.Vpn(config)
                }
                local.getAsJsonObject("config").addProperty("uri", baseConfig.uri)
                val merged = (gson.fromJson<KernelConfig>(mergeJson(baseline, local, remote), KernelConfig::class.java) as KernelConfig.Vpn).config
                val config = merged.copy(uri = mergeUri(baseConfig.uri, old.uri, next.uri, existing.keepSubscriptionAddress)).sanitize()
                require(config.isValid()) { "Новые параметры подписки несовместимы с локальными настройками" }
                return KernelConfig.Vpn(config)
            }
            val merged = gson.fromJson<KernelConfig>(mergeJson(baseline, local, remote), KernelConfig::class.java)
            val selected = if (merged is KernelConfig.MasterDns && existing.kernelConfig is KernelConfig.MasterDns) {
                KernelConfig.MasterDns(merged.config.copy(
                    localResolvers = existing.kernelConfig.config.localResolvers,
                    localDomainResolvers = existing.kernelConfig.config.localDomainResolvers,
                    useLocalResolvers = existing.kernelConfig.config.useLocalResolvers,
                    localPerformanceMode = existing.kernelConfig.config.localPerformanceMode,
                    localPool = existing.kernelConfig.config.localPool))
            } else merged
            val prepared = ClientConfig(kernelConfig = selected).fillDefaults()
            require(prepared.isValid) { "Новые параметры подписки несовместимы с локальными настройками" }
            return prepared.kernelConfig
        }
        // Before baseline tracking, preserve explicitly non-default local tuning; refresh endpoint credentials.
        return when {
            existing.kernelConfig is KernelConfig.MasterDns && incoming.kernelConfig is KernelConfig.MasterDns ->
                KernelConfig.MasterDns(incoming.kernelConfig.config.copy(
                    localResolvers = existing.kernelConfig.config.localResolvers,
                    localDomainResolvers = existing.kernelConfig.config.localDomainResolvers,
                    useLocalResolvers = existing.kernelConfig.config.useLocalResolvers,
                    localPerformanceMode = existing.kernelConfig.config.localPerformanceMode,
                    localPool = existing.kernelConfig.config.localPool
                )).also { it.config.validate() }
            existing.kernelConfig is KernelConfig.Vpn && incoming.kernelConfig is KernelConfig.Vpn -> {
                val old = existing.kernelConfig.config
                val next = incoming.kernelConfig.config
                if (old.protocol != next.protocol) incoming.kernelConfig else KernelConfig.Vpn(next.copy(
                    engine = old.selectedEngine.takeIf { it != "auto" } ?: next.engine,
                    options = legacyOptions(old.options, next.options)
                ).sanitize()).also { require(it.config.isValid()) { "Новые параметры несовместимы с локальными настройками" } }
            }
            existing.kernelConfig is KernelConfig.Webdav && incoming.kernelConfig is KernelConfig.Webdav -> {
                val old = local.getAsJsonObject("config"); val next = remote.getAsJsonObject("config")
                val defaults = snapshot(KernelConfig.Webdav()).getAsJsonObject("config")
                listOf("dns", "timeout", "poll_min", "poll_max", "coalesce", "chunk_size", "puts", "read_min", "read_max").forEach {
                    if (old[it] != defaults[it]) next.add(it, old[it]?.deepCopy())
                }
                gson.fromJson(remote, KernelConfig::class.java)
            }
            else -> incoming.kernelConfig
        }
    }

    private fun mergeUri(base: String, local: String, remote: String, pinned: Boolean): String {
        if (base == local) return remote
        val b = java.net.URI(base); val l = java.net.URI(local); val r = java.net.URI(remote)
        // Non-hierarchical links have no safely separable dial endpoint. Keep the new
        // source intact; explicit per-engine options above still remain local.
        if (b.host == null || l.host == null || r.host == null) return remote
        fun query(uri: java.net.URI) = uri.rawQuery?.split('&')?.filter(String::isNotBlank)
            ?.groupBy { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") }.orEmpty()
        val before = query(b); val edited = query(l); val next = query(r)
        // Mieru repeats interleaved port/protocol fields. Their order is semantic,
        // so never regroup them by key. Conflicting composite edits require review.
        if (listOf(before, edited, next).any { values -> values.values.any { it.size > 1 } }) {
            if (l.rawQuery == b.rawQuery) return remote
            require(r.rawQuery == b.rawQuery || l.rawQuery == r.rawQuery) { "Повторяющиеся параметры изменились локально и в подписке; обновление отменено" }
            return remote.substringBefore('#').substringBefore('?') +
                (l.rawQuery?.let { "?$it" } ?: "") + (r.rawFragment?.let { "#$it" } ?: "")
        }
        // Pinning previously materialized implicit SNI/Host from the old dial name.
        // These are derived fields, not local edits, and must track the new endpoint.
        val derived = if (pinned) setOf("sni", "serverName", "host") else emptySet()
        val parts = (next.keys + edited.keys + before.keys).flatMap { key ->
            val localValues = edited[key]
            val derivedName = key in derived && before[key].orEmpty().all {
                java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8").isBlank()
            }
            if (!derivedName && localValues != before[key]) localValues.orEmpty() else next[key].orEmpty()
        }
        return remote.substringBefore('#').substringBefore('?') +
            (if (parts.isEmpty()) "" else "?" + parts.joinToString("&")) +
            (r.rawFragment?.let { "#$it" } ?: "")
    }
}
