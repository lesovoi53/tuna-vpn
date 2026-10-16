package com.wireturn.app.data.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import java.net.URI

/** Chromium Naive HTTPS links. Authentication is private profile data. */
object NaiveUri {
    fun outbound(value: String): JsonObject {
        require(value.length in 1..32768) { "NaiveProxy: неверная длина ссылки" }
        val uri = URI(value)
        require(uri.scheme.equals("naive+https", true)) { "Поддерживается naive+https://" }
        require(!uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)) { "NaiveProxy: укажите сервер и порт" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "NaiveProxy: путь сервера не поддерживается" }
        val credentials = uri.rawUserInfo.orEmpty().split(':', limit = 2)
        require(credentials.size == 2) { "NaiveProxy: укажите логин и пароль" }
        val username = VpnConfig.decode(credentials[0])
        val password = VpnConfig.decode(credentials[1])
        require(username.isNotEmpty() && password.isNotEmpty()) { "NaiveProxy: укажите логин и пароль" }
        val query = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { part ->
            require('=' in part) { "NaiveProxy: неверный параметр" }
            val key = VpnConfig.decode(part.substringBefore('='))
            require(query.put(key, VpnConfig.decode(part.substringAfter('='))) == null) { "NaiveProxy: повтор параметра $key" }
        }
        require(query.keys.all { it in setOf("sni", "security", "extra-headers", "udp-over-tcp", "udp_over_tcp", "udp-over-tcp-version", "udp_over_tcp_version") }) { "NaiveProxy: неподдерживаемые параметры ссылки" }
        require(query["security"] in setOf(null, "tls")) { "NaiveProxy требует TLS" }
        fun option(name: String): String? {
            val alias = name.replace('-', '_')
            require(!(query.containsKey(name) && query.containsKey(alias))) { "NaiveProxy: повтор параметра $name" }
            return query[name] ?: query[alias]
        }
        val udp = option("udp-over-tcp")?.let {
            when (it) { "true", "1" -> true; "false", "0" -> false; else -> error("NaiveProxy: UDP-over-TCP — true или false") }
        }
        val udpVersion = option("udp-over-tcp-version")?.let {
            require(it in setOf("1", "2")) { "NaiveProxy: версия UDP-over-TCP — 1 или 2" }; it.toInt()
        }
        require(udpVersion == null || udp != null) { "NaiveProxy: укажите true или false для UDP-over-TCP" }
        return JsonObject().apply {
            addProperty("type", "naive"); addProperty("tag", "vpn")
            addProperty("server", uri.host.removeSurrounding("[", "]"))
            addProperty("server_port", if (uri.port == -1) 443 else uri.port)
            addProperty("username", username); addProperty("password", password)
            udp?.let { enabled ->
                if (udpVersion == null) addProperty("udp_over_tcp", enabled)
                else add("udp_over_tcp", JsonObject().apply {
                    addProperty("enabled", enabled); addProperty("version", udpVersion)
                })
            }
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                query["sni"]?.takeIf(String::isNotBlank)?.let { addProperty("server_name", it) }
            })
            query["extra-headers"]?.let { encoded ->
                val headers = JsonParser.parseString(encoded)
                require(headers.isJsonObject) { "NaiveProxy: заголовки должны быть JSON-объектом" }
                headers.asJsonObject.entrySet().forEach { (key, value) ->
                    require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                        !key.equals("Proxy-Authorization", true)) { "NaiveProxy: неверное имя заголовка" }
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                        value.asString.none { it == '\r' || it == '\n' || it == '\u0000' }) { "NaiveProxy: неверное значение заголовка" }
                }
                add("extra_headers", headers)
            }
        }
    }
}

/** sing-box UoT framing requires a compatible server; old Naive profiles stay TCP-only. */
object NaiveUdpOverTcp {
    fun validate(value: JsonElement) {
        if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) return
        require(value.isJsonObject) { "NaiveProxy: udp_over_tcp должен быть boolean или объектом" }
        val options = value.asJsonObject
        require(options.keySet().all { it in setOf("enabled", "version") }) { "NaiveProxy: неизвестный параметр UDP-over-TCP" }
        options["enabled"]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "NaiveProxy: enabled должен быть boolean" }
        }
        options["version"]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString() in setOf("1", "2")) {
                "NaiveProxy: версия UDP-over-TCP — 1 или 2"
            }
        }
    }

    fun enabled(profile: VpnConfig): Boolean = profile.protocol == "naive" && runCatching {
        val value = profile.outbound()["udp_over_tcp"] ?: return@runCatching false
        if (value.isJsonObject) value.asJsonObject["enabled"]?.asBoolean == true else value.asBoolean
    }.getOrDefault(false)

    fun withEnabled(profile: VpnConfig, enabled: Boolean): VpnOptions {
        val options = profile.options ?: VpnOptions()
        val current = runCatching { profile.outbound()["udp_over_tcp"] }.getOrNull()
        val value = if (current?.isJsonObject == true) current.asJsonObject.deepCopy().apply {
            addProperty("enabled", enabled)
        } else JsonPrimitive(enabled)
        return options.withEngine("sing-box", NativeOptions.put(options.forEngine("sing-box"), "udp_over_tcp", value))
    }
}
