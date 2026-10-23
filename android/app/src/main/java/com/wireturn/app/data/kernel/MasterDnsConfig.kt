package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.Locale
import com.wireturn.app.domain.MasterDnsEndpoint

data class MasterDnsConfig(
    val name: String = "MasterDnsVPN",
    val domains: List<String> = emptyList(),
    val key: String = "",
    val encryption: Int = 5,
    val resolvers: String = "",
    val settings: Map<String, String> = emptyMap(),
    val originalJson: String = "",
    // Nullable additions keep older Gson profile snapshots compatible. Source resolvers
    // remain intact so switching back also adopts the latest subscription refresh.
    val localResolvers: String? = null,
    val useLocalResolvers: Boolean? = null,
    // Null inherits the original profile, including when read from older Gson storage.
    val localPerformanceMode: String? = null,
    // Local profile IDs are intentionally excluded from portable stormdns links.
    val localPool: MasterDnsPoolSelection? = null,
    // Null preserves legacy shared selection; empty/missing entries disable that domain.
    val localDomainResolvers: Map<String, String>? = null
) {
    fun value(field: MasterDnsField): String = settings[field.key] ?: field.defaultValue
    fun performanceMode(): MasterDnsPerformanceMode? = localPerformanceMode?.let(MasterDnsPerformanceMode::fromWire)
    fun effectiveValue(field: MasterDnsField): String = performanceMode()?.overrides?.get(field.key) ?: value(field)
    fun profileResolverList() = parseResolvers(resolvers)
    fun localResolverList(domain: String): List<String> = localDomainResolvers?.let { selected ->
        parseResolvers(selected.entries.firstOrNull { it.key.equals(domain, ignoreCase = true) }?.value.orEmpty())
    } ?: parseResolvers(localResolvers.orEmpty())
    fun localResolverList() = domains.flatMap(::localResolverList).distinct()
    fun resolverList(domain: String) = if (useLocalResolvers == true) localResolverList(domain) else profileResolverList()
    fun resolverList() = domains.flatMap(::resolverList).distinct()
    /** Native resolver files require canonical IP:port, including bracketed IPv6. */
    fun runtimeResolverList() = resolverList().map {
        requireNotNull(MasterDnsEndpoint.parse(it)) { "Некорректный резолвер" }.canonical
    }.distinct()
    fun effectiveDomainResolvers(): Map<String, List<String>> = domains.associateWith(::resolverList)
    fun validate() {
        localPool?.validate()
        performanceMode() // Reject invalid persisted overrides instead of silently running another mode.
        require(domains.isNotEmpty() && domains.size <= 16 && domains.all {
            it.length in 1..253 && it.split('.').all { label -> label.length in 1..63 && label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) }
        }) { "Проверьте домены туннеля" }
        require(domains.map { it.lowercase(Locale.ROOT) }.distinct().size == domains.size) {
            "Домены туннеля не должны повторяться (регистр не учитывается)"
        }
        require(key.isNotBlank() && key.length <= 4096 && encryption in 0..5) { "Проверьте ключ и метод шифрования" }
        validateResolvers(profileResolverList())
        if (localResolvers != null && localResolvers.isNotBlank()) validateResolvers(parseResolvers(localResolvers))
        localDomainResolvers?.let { selected ->
            require(selected.size <= 16 && selected.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == selected.size) {
                "Некорректный набор доменов резолверов"
            }
            selected.forEach { (domain, text) ->
                require(domain.length in 1..253 && domain.split('.').all { label ->
                    label.length in 1..63 && label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                }) { "Некорректный домен резолверов" }
                val endpoints = parseResolvers(text)
                if (endpoints.isNotEmpty()) validateResolvers(endpoints)
            }
        }
        if (useLocalResolvers == true) {
            require(localResolverList().isNotEmpty()) { "Выберите резолверы хотя бы для одного домена" }
            if (localDomainResolvers == null) validateResolvers(localResolverList())
        }
        settings.forEach { (key, value) ->
            val f = requireNotNull(MasterDnsFields.byKey[key]) { "Неизвестная настройка ядра: $key" }
            validateValue(f, value)
        }
        fun n(k: String) = value(MasterDnsFields.byKey.getValue(k)).toDouble()
        require(n("MIN_UPLOAD_MTU") <= n("MAX_UPLOAD_MTU") && n("MIN_DOWNLOAD_MTU") <= n("MAX_DOWNLOAD_MTU")) { "Минимальный MTU больше максимального" }
        require(n("RESOLVER_BALANCING_STRATEGY") in 0.0..8.0) { "Стратегия: 0–8" }
        for (k in listOf("UPLOAD_COMPRESSION_TYPE", "DOWNLOAD_COMPRESSION_TYPE")) require(n(k) in 0.0..3.0) { "Сжатие: 0–3" }
    }
    fun isValid() = runCatching { validate() }.isSuccess

    fun toml(listen: String, user: String, password: String, auth: Boolean): String {
        validate()
        val host = listen.substringBeforeLast(':').removeSurrounding("[", "]")
        require(host in listOf("127.0.0.1", "::1")) { "MasterDnsVPN слушает только loopback" }
        val port = listen.substringAfterLast(':').toInt()
        require(port in 1..65535)
        val g = Gson()
        return buildString {
            appendLine("DOMAINS = ${g.toJson(domains)}")
            if (useLocalResolvers == true && localDomainResolvers != null) {
                val pairs = effectiveDomainResolvers().entries.joinToString(", ") { (domain, endpoints) ->
                    "${g.toJson(domain.lowercase(Locale.ROOT))} = ${g.toJson(endpoints.map { requireNotNull(MasterDnsEndpoint.parse(it)).canonical })}"
                }
                appendLine("TUNA_DOMAIN_RESOLVERS = { $pairs }")
            }
            appendLine("DATA_ENCRYPTION_METHOD = $encryption")
            appendLine("ENCRYPTION_KEY = ${g.toJson(key)}")
            appendLine("PROTOCOL_TYPE = \"SOCKS5\"")
            appendLine("LISTEN_IP = ${g.toJson(host)}")
            appendLine("LISTEN_PORT = $port")
            appendLine("SOCKS5_AUTH = $auth")
            appendLine("SOCKS5_USER = ${g.toJson(user)}")
            appendLine("SOCKS5_PASS = ${g.toJson(password)}")
            // Existing TUNA fake-DNS handles application DNS; no additional unauthenticated UDP listener.
            appendLine("LOCAL_DNS_ENABLED = false")
            MasterDnsFields.all.forEach { f ->
                val v = effectiveValue(f)
                appendLine("${f.key} = ${if (f.type == "string") g.toJson(v) else v}")
            }
        }
    }

    fun toUri(): String {
        require(localPool == null) {
            "Пул Master DNS содержит локальные серверы и не поддерживает экспорт ссылкой. Сначала отключите пул."
        }
        validate()
        val root = if (originalJson.isBlank()) JsonObject() else JsonParser.parseString(originalJson).asJsonObject
        stripLocalPoolFields(root)
        root.addProperty("schema", "whitedns.profile"); root.addProperty("version", 1)
        val p = root.getAsJsonObject("profile") ?: JsonObject().also { root.add("profile", it) }
        p.addProperty("name", name)
        p.add("domains", Gson().toJsonTree(domains))
        val server = p.getAsJsonObject("server") ?: JsonObject().also { p.add("server", it) }
        server.addProperty("domain", domains.firstOrNull().orEmpty()); server.addProperty("encryption_key", key); server.addProperty("encryption_method", encryption)
        p.addProperty("resolvers", resolvers)
        // Preserve the portable source list and explicitly describe the TUNA-only choice.
        p.remove("tuna_resolver_selection")
        if (localResolvers != null || useLocalResolvers != null || localDomainResolvers != null) {
            p.add("tuna_resolver_selection", JsonObject().apply {
                addProperty("source", if (useLocalResolvers == true) "local" else "profile")
                localResolvers?.let { addProperty("resolvers", it) }
                localDomainResolvers?.let { add("domain_resolvers", Gson().toJsonTree(it)) }
            })
        }
        // This TUNA-only extension never replaces the portable source settings.
        p.remove("tuna_performance_mode")
        localPerformanceMode?.let { p.addProperty("tuna_performance_mode", it) }
        p.keySet().toList().filter { it.uppercase(Locale.ROOT) in MasterDnsFields.byKey }.forEach { p.remove(it) }
        settings.forEach { (k,v) ->
            val f = MasterDnsFields.byKey.getValue(k)
            val key = k.lowercase(Locale.ROOT)
            when(f.type) {
                "bool" -> p.addProperty(key, v.toBooleanStrict())
                "int" -> p.addProperty(key, v.toInt())
                "float64" -> p.add(key, JsonParser.parseString(v))
                else -> p.addProperty(key, v)
            }
        }
        return "stormdns://" + Base64.getEncoder().encodeToString(root.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        private fun stripLocalPoolFields(value: com.google.gson.JsonElement) {
            if (value.isJsonObject) {
                val objectValue = value.asJsonObject
                listOf("localPool", "local_pool", "tuna_local_pool", "tuna_pool", "tuna_pool_selection")
                    .forEach(objectValue::remove)
                objectValue.entrySet().forEach { (_, child) -> stripLocalPoolFields(child) }
            } else if (value.isJsonArray) {
                value.asJsonArray.forEach(::stripLocalPoolFields)
            }
        }
        private fun parseResolvers(text: String) =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

        private fun validateResolvers(values: List<String>) {
            require(values.size in 1..128 && values.all {
                MasterDnsEndpoint.parse(it) != null
            }) { "Нужны IP резолверов, каждый на отдельной строке (не более 128)" }
        }

        fun validateValue(f: MasterDnsField, v: String) {
            val valid = when(f.type) {
                "bool" -> v == "true" || v == "false"
                "int" -> v.toIntOrNull()?.let { it >= 0 } == true
                "float64" -> v.toDoubleOrNull()?.let { it.isFinite() && it >= 0 && it <= 1e9 } == true
                else -> v.length <= 2048 && !v.contains('\u0000')
            }
            require(valid) { "Некорректное значение: ${f.title}" }
            if (f.key == "MTU_SERVERS_FILE_NAME") require(v.isNotBlank() && !v.contains('/') && !v.contains('\\') && v != "..") { "Нужно имя файла без пути" }
        }
        fun parse(uri: String): MasterDnsConfig? = runCatching {
            val input = uri.trim()
            require(input.startsWith("stormdns://", true) && input.length <= 100000)
            val encoded = input.substringAfter("://")
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { Base64.getUrlDecoder().decode(encoded) }
            require(bytes.size <= 65536)
            val raw = bytes.toString(Charsets.UTF_8)
            val root = JsonParser.parseString(raw).asJsonObject
            stripLocalPoolFields(root)
            require(root["schema"].asString == "whitedns.profile" && root["version"].asString == "1")
            val p = root.getAsJsonObject("profile")
            val s = p.getAsJsonObject("server")
            val selection = p.get("tuna_resolver_selection")?.let {
                require(it.isJsonObject) { "Некорректный выбор резолверов" }
                it.asJsonObject.also { choice ->
                    require(choice.keySet().all { key -> key in setOf("source", "resolvers", "domain_resolvers") })
                    require(choice["source"]?.isJsonPrimitive == true &&
                        choice["source"].asJsonPrimitive.isString &&
                        choice["source"].asString in setOf("local", "profile"))
                    choice["domain_resolvers"]?.let { values ->
                        require(values.isJsonObject && values.asJsonObject.size() <= 16)
                        require(values.asJsonObject.entrySet().all { (_, value) -> value.isJsonPrimitive && value.asJsonPrimitive.isString })
                    }
                    choice["resolvers"]?.let { values ->
                        require(values.isJsonPrimitive && values.asJsonPrimitive.isString)
                    }
                }
            }
            val performanceMode = p.get("tuna_performance_mode")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Некорректный режим MasterDnsVPN" }
                MasterDnsPerformanceMode.fromWire(it.asString).wireValue
            }
            val options = mutableMapOf<String,String>()
            p.entrySet().forEach { (k,v) ->
                val key = k.uppercase(Locale.ROOT)
                if (key in MasterDnsFields.byKey) {
                    require(v.isJsonPrimitive)
                    options[key] = v.asString
                }
            }
            MasterDnsConfig(p["name"]?.asString ?: "MasterDnsVPN",
                p["domains"]?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: listOf(s["domain"].asString),
                s["encryption_key"].asString, s["encryption_method"].asInt,
                p["resolvers"].asString, options, root.toString(),
                localResolvers = selection?.get("resolvers")?.asString,
                useLocalResolvers = selection?.get("source")?.asString?.let { it == "local" },
                localPerformanceMode = performanceMode,
                localDomainResolvers = selection?.getAsJsonObject("domain_resolvers")?.entrySet()?.associate { it.key to it.value.asString }
            ).also { it.validate() }
        }.getOrNull()
    }
}
