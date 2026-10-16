package com.wireturn.app.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.wireturn.app.data.kernel.VpnConfig
import java.io.File
import java.io.StringReader
import java.net.URI

/** Private full-document profiles. Adapt only the application's listener and diagnostics. */
object RawXrayConfig {
    const val MAX_LENGTH = 4 * 1024 * 1024
    const val INBOUND_TAG = "tuna-json-in"
    private val topLevel = setOf("remarks", "name", "log", "dns", "routing", "inbounds", "outbounds",
        "policy", "stats", "observatory", "burstObservatory", "fakedns")
    private val blockedKeys = setOf("certificateFile", "keyFile", "masterKeyLog", "masqFile", "geodata",
        "interface", "mark", "tproxy", "system", "kernelMode", "reverse", "sendThrough")

    /** Strict parsing also rejects duplicate keys; parser errors never echo private input. */
    fun parse(raw: String): JsonObject {
        require(raw.length in 1..MAX_LENGTH) { "JSON Xray: размер должен быть от 1 байта до 4 МиБ" }
        val root = parseContainer(raw)
        require(root.isJsonObject) { "JSON Xray: нужен один объект конфигурации" }
        return root.asJsonObject
    }

    /** Import may contain one document or an array of independent full documents. */
    fun parseContainer(raw: String): JsonElement {
        require(raw.length in 1..(MAX_LENGTH * 4)) { "JSON Xray: подписка слишком велика" }
        try {
            return JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                fun read(depth: Int): JsonElement {
                    require(depth <= 48) { "JSON Xray: слишком глубокая вложенность" }
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                require(!has(key)) { "JSON Xray: повторяющееся поле" }
                                add(key, read(depth + 1))
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                            reader.beginArray()
                            while (reader.hasNext()) add(read(depth + 1))
                            reader.endArray()
                        }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString())
                        JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString()))
                        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                        else -> error("invalid token")
                    }
                }
                val root = read(0)
                require((root.isJsonObject || root.isJsonArray) && reader.peek() == JsonToken.END_DOCUMENT)
                root
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("JSON Xray: нужен один корректный объект без повторяющихся полей")
        }
    }

    fun hasBalancer(raw: String): Boolean = runCatching {
        parse(raw).getAsJsonObject("routing")?.getAsJsonArray("balancers")?.size()?.let { it > 0 } == true
    }.getOrDefault(false)

    fun displayName(raw: String): String = runCatching {
        val root = parse(raw)
        sequenceOf("remarks", "name").mapNotNull { root[it]?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString }
            .firstOrNull { it.isNotBlank() }?.take(256)
    }.getOrNull() ?: "Xray JSON"

    fun validate(profile: VpnConfig) {
        require(profile.isRawXray) { "Нет полного JSON Xray" }
        require((profile.options?.xray?.size() ?: 0) == 0 && (profile.options?.singBox?.size() ?: 0) == 0 &&
            profile.options?.fragmentation?.mode.let { it == null || it == "off" }) {
            "Полный JSON Xray: изменяйте настройки outbound и фрагментацию в самом JSON"
        }
        validateDocument(parse(profile.rawXrayJson.orEmpty()))
    }

    private fun string(node: JsonObject, key: String): String? = node[key]?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "JSON Xray: ожидается строковое поле" }
        it.asString
    }

    /** Xray StringList accepts either a JSON array or a comma-separated string. */
    private fun stringList(value: JsonElement): List<String> {
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString.split(',')
        require(value.isJsonArray && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) {
            "JSON Xray: ожидается строка или массив строк"
        }
        return value.asJsonArray.map { it.asString }
    }

    private fun validateDocument(root: JsonObject) {
        require(root.keySet().all { it in topLevel }) {
            "JSON Xray: неподдерживаемый раздел; API, metrics, reverse и внешние слушатели недоступны"
        }
        val outbounds = root["outbounds"]
        require(outbounds?.isJsonArray == true && outbounds.asJsonArray.size() in 1..2048) {
            "JSON Xray: нужен непустой массив outbounds (до 2048)"
        }
        val tags = mutableSetOf<String>()
        outbounds.asJsonArray.forEach { entry ->
            require(entry.isJsonObject) { "JSON Xray: outbound должен быть объектом" }
            val out = entry.asJsonObject
            require(!string(out, "protocol").isNullOrBlank()) { "JSON Xray: outbound без протокола" }
            if (string(out, "protocol") == "wireguard") require(out.getAsJsonObject("settings")?.get("noKernelTun")?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
            } == true) { "JSON Xray: WireGuard требует noKernelTun=true" }
            string(out, "tag")?.let { require(it.isNotBlank() && tags.add(it)) { "JSON Xray: пустой или повторяющийся тег outbound" } }
        }
        val inboundTags = mutableSetOf<String>()
        root["inbounds"]?.let { entries ->
            require(entries.isJsonArray) { "JSON Xray: inbounds должен быть массивом" }
            entries.asJsonArray.forEach { entry ->
                require(entry.isJsonObject && string(entry.asJsonObject, "protocol") in setOf("socks", "http", "mixed")) {
                    "JSON Xray: входы сервера/TUN не поддержаны; клиент заменяет SOCKS/HTTP/mixed своим локальным входом"
                }
                string(entry.asJsonObject, "tag")?.let { require(it.isNotBlank() && inboundTags.add(it)) { "JSON Xray: повторяющийся тег inbound" } }
            }
            require(entries.asJsonArray.mapNotNull { it.asJsonObject["sniffing"] }.distinct().size <= 1) {
                "JSON Xray: разные настройки sniffing локальных входов нельзя объединить"
            }
        }
        for (key in listOf("dns", "routing", "policy", "stats", "observatory", "burstObservatory", "log")) {
            root[key]?.let { require(it.isJsonObject) { "JSON Xray: раздел $key должен быть объектом" } }
        }
        root.getAsJsonObject("log")?.let { log ->
            for (field in listOf("access", "error")) string(log, field)?.let {
                require(it in setOf("", "none")) { "JSON Xray: файловые журналы не поддерживаются" }
            }
        }
        val balancerTags = mutableSetOf<String>()
        root.getAsJsonObject("routing")?.let { routing ->
            routing["balancers"]?.let { entries ->
                require(entries.isJsonArray) { "JSON Xray: balancers должен быть массивом" }
                entries.asJsonArray.forEach { entry ->
                    require(entry.isJsonObject) { "JSON Xray: балансировщик должен быть объектом" }
                    val balancer = entry.asJsonObject
                    val tag = string(balancer, "tag")
                    require(!tag.isNullOrBlank() && balancerTags.add(tag)) { "JSON Xray: пустой или повторяющийся тег балансировщика" }
                    val selector = balancer["selector"]?.let(::stringList).orEmpty()
                    require(selector.isNotEmpty() && selector.all { prefix -> tags.any { tag -> tag.startsWith(prefix) } }) {
                        "JSON Xray: selector балансировщика не выбирает существующие outbounds"
                    }
                    string(balancer, "fallbackTag")?.let { require(it in tags) { "JSON Xray: неизвестный fallbackTag" } }
                }
            }
            routing["rules"]?.let { entries ->
                require(entries.isJsonArray) { "JSON Xray: rules должен быть массивом" }
                entries.asJsonArray.forEach { entry ->
                    require(entry.isJsonObject) { "JSON Xray: правило должно быть объектом" }
                    val rule = entry.asJsonObject
                    string(rule, "outboundTag")?.let { require(it in tags) { "JSON Xray: правило ссылается на неизвестный outboundTag" } }
                    string(rule, "balancerTag")?.let { require(it in balancerTags) { "JSON Xray: правило ссылается на неизвестный balancerTag" } }
                    rule["inboundTag"]?.let { references ->
                        // One app input cannot preserve distinct rules for different source listeners.
                        val referenced = stringList(references).toSet()
                        val local = referenced.intersect(inboundTags)
                        require(local.isEmpty() || local == inboundTags) {
                            "JSON Xray: раздельные правила для нескольких локальных входов не могут быть объединены"
                        }
                    }
                }
            }
        }
        fun walk(node: JsonElement, path: String) {
            if (node.isJsonObject) node.asJsonObject.entrySet().forEach { (key, value) ->
                // policy.system is native accounting, not a system-network binding.
                require(key !in blockedKeys || (key == "system" && path == "policy")) {
                    "JSON Xray: файловые пути, сетевые привязки и системные интерфейсы не поддерживаются"
                }
                require(key != "dir" || !path.endsWith("masquerade")) { "JSON Xray: файловая masquerade не поддерживается" }
                walk(value, if (path.isEmpty()) key else "$path.$key")
            } else if (node.isJsonArray) node.asJsonArray.forEach { walk(it, path) }
            else if (node.isJsonPrimitive && node.asJsonPrimitive.isString) {
                val value = node.asString
                require(!value.startsWith("ext:", true) && !value.startsWith("ext-ip:", true)) {
                    "JSON Xray: внешние файлы ext/ext-ip не поддерживаются; используйте установленные geoip/geosite"
                }
            }
        }
        // Source listeners are replaced, but inspect them too so imported files cannot hide file reads.
        walk(root, "")
    }

    fun requiredGeoAssets(profile: VpnConfig): Set<String> {
        if (!profile.isRawXray) return emptySet()
        val required = mutableSetOf<String>()
        fun walk(node: JsonElement) {
            if (node.isJsonObject) node.asJsonObject.entrySet().forEach { walk(it.value) }
            else if (node.isJsonArray) node.asJsonArray.forEach(::walk)
            else if (node.isJsonPrimitive && node.asJsonPrimitive.isString) {
                if (node.asString.startsWith("geoip:", true)) required.add("geoip.dat")
                if (node.asString.startsWith("geosite:", true)) required.add("geosite.dat")
            }
        }
        walk(parse(profile.rawXrayJson.orEmpty()))
        return required
    }

    fun validateAssets(profile: VpnConfig, directory: File?) {
        for (name in requiredGeoAssets(profile)) require(directory != null && File(directory, name).let { it.isFile && it.length() > 0 }) {
            "JSON Xray: не установлена база $name; загрузите георесурсы в настройках маршрутизации"
        }
    }

    fun generate(profile: VpnConfig, listen: String, user: String, password: String, allowNoAuth: Boolean): JsonObject {
        validate(profile)
        val address = URI("socks://$listen")
        require(address.host in setOf("127.0.0.1", "[::1]", "::1") && address.port in 1..65535) { "JSON Xray: нужен локальный SOCKS-вход" }
        require(allowNoAuth || user.isNotBlank() && password.isNotBlank()) { "JSON Xray: не заданы локальные учётные данные" }
        val root = parse(profile.rawXrayJson.orEmpty())
        val oldTags = root.getAsJsonArray("inbounds")?.mapNotNull { string(it.asJsonObject, "tag") }?.toSet().orEmpty()
        // Reserve the generated inbound tag from logical DNS traffic tags as well.
        require(root.getAsJsonObject("dns")?.get("tag")?.asString != INBOUND_TAG) { "JSON Xray: зарезервированный тег DNS" }
        root.getAsJsonObject("routing")?.getAsJsonArray("rules")?.forEach { entry ->
            entry.asJsonObject["inboundTag"]?.let { original ->
                entry.asJsonObject.add("inboundTag", JsonArray().apply {
                    stringList(original).map { if (it in oldTags) INBOUND_TAG else it }.distinct().forEach(::add)
                })
            }
        }
        val sniffing = root.getAsJsonArray("inbounds")?.mapNotNull { it.asJsonObject["sniffing"] }?.distinct()
        require(sniffing == null || sniffing.size <= 1) { "JSON Xray: разные настройки sniffing локальных входов нельзя объединить" }
        root.add("inbounds", JsonArray().apply { add(JsonObject().apply {
            addProperty("tag", INBOUND_TAG); addProperty("listen", address.host.removeSurrounding("[", "]"))
            addProperty("port", address.port); addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", if (allowNoAuth) "noauth" else "password"); addProperty("udp", true)
                addProperty("ip", "127.0.0.1")
                if (!allowNoAuth) add("accounts", JsonArray().apply { add(JsonObject().apply { addProperty("user", user); addProperty("pass", password) }) })
            })
            add("sniffing", sniffing?.firstOrNull()?.deepCopy() ?: JsonObject().apply {
                addProperty("enabled", true); addProperty("routeOnly", true)
                add("destOverride", JsonArray().apply { add("http"); add("tls"); add("quic"); if (root.has("fakedns")) add("fakedns") })
            })
        }) })
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning"); addProperty("access", "none"); addProperty("dnsLog", false) })
        root.remove("remarks"); root.remove("name")
        return JsonObject().apply {
            add("raw_xray", root); addProperty("listen", listen)
            if (!allowNoAuth) { addProperty("proxy_user", user); addProperty("proxy_pass", password) }
        }
    }
}
