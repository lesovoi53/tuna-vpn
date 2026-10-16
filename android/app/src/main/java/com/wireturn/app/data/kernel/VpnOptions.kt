package com.wireturn.app.data.kernel

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Versioned, engine-specific native settings. A missing value in an old profile means defaults. */
data class VpnOptions(
    val version: Int = 1,
    val singBox: JsonObject? = null,
    val xray: JsonObject? = null,
    val allowExperimentalSnellTfo: Boolean = false,
    val fragmentation: VpnFragmentation? = null
) {
    fun forEngine(engine: String): JsonObject = (if (engine == "xray") xray else singBox)?.deepCopy() ?: JsonObject()
    fun withEngine(engine: String, value: JsonObject) = if (engine == "xray") copy(xray = value.deepCopy()) else copy(singBox = value.deepCopy())
    fun validate() {
        require(version == 1) { "Неизвестная версия дополнительных настроек: $version" }
        fragmentation?.validate()
    }
}

object NativeOptions {
    fun parse(text: String): JsonObject {
        require(text.length <= 262144) { "Конфигурация слишком велика" }
        val value = JsonParser.parseString(text.ifBlank { "{}" })
        require(value.isJsonObject) { "Нужен JSON-объект настроек outbound" }
        return value.asJsonObject
    }

    fun get(root: JsonObject, path: String): JsonElement? {
        var node: JsonElement = root
        for (part in path.split('.')) node = if (node.isJsonObject) node.asJsonObject.get(part) ?: return null else return null
        return node
    }

    fun put(root: JsonObject, path: String, value: JsonElement?): JsonObject {
        val result = root.deepCopy()
        val parts = path.split('.')
        fun update(node: JsonObject, index: Int) {
            val key = parts[index]
            if (index == parts.lastIndex) { if (value == null) node.remove(key) else node.add(key, value.deepCopy()); return }
            val child = node.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            update(child, index + 1)
            if (child.size() == 0) node.remove(key) else node.add(key, child)
        }
        update(result, 0)
        return result
    }

    fun merge(base: JsonObject, patch: JsonObject): JsonObject = base.deepCopy().apply {
        for ((key, value) in patch.entrySet()) {
            require(!value.isJsonNull) { "$key: null не поддержан; удалите поле для значения по умолчанию" }
            if (value.isJsonObject && get(key)?.isJsonObject == true) add(key, merge(getAsJsonObject(key), value.asJsonObject))
            else add(key, value.deepCopy())
        }
    }

    fun validatePatch(engine: String, patch: JsonObject, protocol: String) {
        require(patch.toString().length <= 262144)
        val blocked = if (engine == "xray") setOf("protocol", "tag", "proxySettings", "sendThrough")
            else setOf("type", "tag", "detour", "bind_interface", "inet4_bind_address", "inet6_bind_address", "protect_path", "routing_mark", "netns")
        require(patch.keySet().none { it in blocked }) { "Тип, служебные маршруты и привязки outbound управляются TUNA" }
        fun walk(node: JsonElement, depth: Int) {
            require(depth < 24) { "Слишком глубокая вложенность JSON" }
            require(!node.isJsonNull) { "Удалите null-поля из JSON" }
            if (node.isJsonObject) for ((key, value) in node.asJsonObject.entrySet()) {
                require(key !in setOf("dialerProxy", "detour", "sockoptMark", "interface", "netns", "protect_path")) { "$key управляется TUNA" }
                walk(value, depth + 1)
            } else if (node.isJsonArray) node.asJsonArray.forEach { walk(it, depth + 1) }
        }
        walk(patch, 0)
        if (protocol == "mieru") require(patch.size() == 0) { "Mieru использует отдельное ядро и параметры ссылки" }
        if (engine == "sing-box" && protocol == "snell") {
            require(!patch.has("version") || patch["version"].asInt in setOf(4,6)) { "Ядро поддерживает Snell v4 и v6" }
        }
        if (protocol in setOf("awg", "wireguard")) require(patch.keySet().none { it in setOf("system", "name", "listen_port") }) { "Интерфейс WireGuard управляется TUNA" }
        CoreCapabilities.fields(engine, protocol).forEach { field -> get(patch, field.path)?.let { field.validate(it) } }
    }
}
