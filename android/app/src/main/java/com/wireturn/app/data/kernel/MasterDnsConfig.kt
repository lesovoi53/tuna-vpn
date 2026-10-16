package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.Locale

data class MasterDnsConfig(
    val name: String = "MasterDnsVPN",
    val domains: List<String> = emptyList(),
    val key: String = "",
    val encryption: Int = 5,
    val resolvers: String = "",
    val settings: Map<String, String> = emptyMap(),
    val originalJson: String = ""
) {
    fun value(field: MasterDnsField): String = settings[field.key] ?: field.defaultValue
    fun resolverList() = resolvers.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    fun validate() {
        require(domains.isNotEmpty() && domains.size <= 16 && domains.all {
            it.length in 1..253 && it.split('.').all { label -> label.length in 1..63 && label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) }
        }) { "Проверьте домены туннеля" }
        require(key.isNotBlank() && key.length <= 4096 && encryption in 0..5) { "Проверьте ключ и метод шифрования" }
        require(resolverList().size in 1..128 && resolverList().all { it.length <= 128 && it.matches(Regex("[0-9a-fA-F.:\\[\\]]+")) }) { "Нужны IP резолверов, каждый на отдельной строке (не более 128)" }
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
                val v = value(f)
                appendLine("${f.key} = ${if (f.type == "string") g.toJson(v) else v}")
            }
        }
    }

    fun toUri(): String {
        validate()
        val root = if (originalJson.isBlank()) JsonObject() else JsonParser.parseString(originalJson).asJsonObject
        root.addProperty("schema", "whitedns.profile"); root.addProperty("version", 1)
        val p = root.getAsJsonObject("profile") ?: JsonObject().also { root.add("profile", it) }
        p.addProperty("name", name)
        p.add("domains", Gson().toJsonTree(domains))
        val server = p.getAsJsonObject("server") ?: JsonObject().also { p.add("server", it) }
        server.addProperty("domain", domains.firstOrNull().orEmpty()); server.addProperty("encryption_key", key); server.addProperty("encryption_method", encryption)
        p.addProperty("resolvers", resolvers)
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
            require(root["schema"].asString == "whitedns.profile" && root["version"].asString == "1")
            val p = root.getAsJsonObject("profile")
            val s = p.getAsJsonObject("server")
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
                p["resolvers"].asString, options, raw).also { it.validate() }
        }.getOrNull()
    }
}
