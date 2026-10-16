package com.wireturn.app.data.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

/** URI adapters for protocols registered in the pinned extended binary. */
object AdditionalVpnProtocols {
    val protocols = setOf("trojan", "shadowsocks", "tuic", "anytls")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun base64(s: String): String = runCatching { Base64.getUrlDecoder().decode(s) }
        .getOrElse { Base64.getDecoder().decode(s) }.toString(Charsets.UTF_8)

    // SIP002 and the legacy whole-authority Base64 form become an editable URI.
    // Split method only once: SS2022 identity chains are part of the password.
    fun normalize(uri: String): String {
        if (!uri.startsWith("ss://", true)) return uri
        val body = uri.substringAfter("://").substringBefore('#')
        val suffix = uri.substringAfter('#', "").takeIf { '#' in uri }?.let { "#$it" }.orEmpty()
        val legacy = '@' !in body
        val expanded = if (legacy) base64(body.substringBefore('?')) +
            (if ('?' in body) "?" + body.substringAfter('?') else "") else body
        val credential = expanded.substringBeforeLast('@')
        require('@' in expanded)
        val plain = if (legacy) credential else if (':' in credential) VpnConfig.decode(credential) else base64(VpnConfig.decode(credential))
        require(':' in plain) { "Shadowsocks: нужен метод и пароль" }
        return "ss://${enc(plain.substringBefore(':'))}:${enc(plain.substringAfter(':'))}@${expanded.substringAfterLast('@')}$suffix"
    }

    fun outbound(profile: VpnConfig): JsonObject {
        val u = URI(normalize(profile.uri))
        val protocol = profile.protocol
        require(protocol in protocols && profile.uri.length in 1..32768)
        require(!u.host.isNullOrBlank() && u.port in 1..65535) { "Укажите сервер и порт" }
        require(u.path.isNullOrEmpty() || u.path == "/") { "Путь задаётся параметром path" }
        val q = linkedMapOf<String, String>()
        u.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach {
            require('=' in it) { "Неверный параметр ссылки" }
            require(q.put(VpnConfig.decode(it.substringBefore('=')), VpnConfig.decode(it.substringAfter('='))) == null) { "Повтор параметра ссылки" }
        }
        val tlsKeys = setOf("security", "sni", "alpn", "fp", "allowInsecure", "allow_insecure", "insecure")
        val allowed = when (protocol) {
            "trojan" -> tlsKeys + setOf("type", "path", "host", "serviceName")
            "tuic" -> tlsKeys + setOf("congestion_control", "udp_relay_mode", "zero_rtt_handshake", "heartbeat", "udp_over_stream")
            "anytls" -> tlsKeys + setOf("idle_session_check_interval", "idle_session_timeout", "min_idle_session")
            else -> setOf("type")
        }
        require(q.keys.all { it in allowed }) { "Неподдерживаемые параметры: ${q.keys.filter { it !in allowed }.joinToString()}" }
        fun bool(key: String) = q[key]?.let { when(it) {
            "true", "1" -> true; "false", "0" -> false; else -> error("$key: требуется true или false")
        } }
        val out = JsonObject().apply {
            addProperty("type", protocol); addProperty("tag", "vpn")
            addProperty("server", u.host.removeSurrounding("[", "]")); addProperty("server_port", u.port)
        }
        val credential = u.rawUserInfo.orEmpty()
        require(credential.isNotBlank()) { "Укажите реквизиты" }
        if (protocol in setOf("tuic", "shadowsocks")) {
            require(':' in credential) { "Укажите оба поля реквизитов" }
            val first = VpnConfig.decode(credential.substringBefore(':'))
            val password = VpnConfig.decode(credential.substringAfter(':'))
            require(password.isNotBlank()) { "Пароль пуст" }
            out.addProperty("password", password)
            if (protocol == "tuic") {
                require(UUID.fromString(first).toString().equals(first, true)) { "Неверный UUID TUIC" }
                out.addProperty("uuid", first)
            } else {
                require(first in setOf("aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")) { "Метод Shadowsocks пока не поддержан редактором" }
                if (first.startsWith("2022-")) {
                    val keySize = if (first == "2022-blake3-aes-128-gcm") 16 else 32
                    require(password.split(':').all { runCatching { Base64.getDecoder().decode(it).size == keySize }.getOrDefault(false) }) { "Неверная длина ключа Shadowsocks 2022" }
                }
                require(q["type"] in setOf(null, "tcp")) { "Внешний транспорт Shadowsocks не поддержан" }
                out.addProperty("method", first)
            }
        } else out.addProperty("password", VpnConfig.decode(credential))
        if (protocol != "shadowsocks") {
            require(q["security"] in setOf(null, "tls")) { "Этот редактор требует TLS" }
            val insecureKeys = listOf("insecure", "allowInsecure", "allow_insecure").filter { it in q }
            require(insecureKeys.size <= 1) { "Повтор параметра проверки TLS" }
            if (protocol == "tuic") require(q["fp"].isNullOrBlank()) { "TCP fingerprint неприменим к TUIC/QUIC" }
            out.add("tls", JsonObject().apply {
                addProperty("enabled", true)
                q["sni"]?.let { addProperty("server_name", it) }
                insecureKeys.singleOrNull()?.let { addProperty("insecure", bool(it)) }
                q["alpn"]?.let { value -> add("alpn", JsonArray().apply { value.split(',').forEach { require(it.isNotBlank()); add(it) } }) }
                q["fp"]?.let { add("utls", JsonObject().apply { addProperty("enabled", true); addProperty("fingerprint", it) }) }
            })
        }
        when(protocol) {
            "trojan" -> {
                val transport = q["type"] ?: "tcp"
                require(transport in setOf("tcp", "ws", "grpc", "httpupgrade")) { "Неподдерживаемый транспорт Trojan" }
                require(transport != "tcp" || q.keys.none { it in setOf("path", "host", "serviceName") }) { "Поля транспорта требуют WS/gRPC/HTTPUpgrade" }
                if (transport != "tcp") out.add("transport", JsonObject().apply {
                    addProperty("type", transport)
                    if (transport == "grpc") {
                        require(q.keys.none { it in setOf("path", "host") })
                        addProperty("service_name", q["serviceName"].orEmpty())
                    } else {
                        require("serviceName" !in q)
                        addProperty("path", q["path"] ?: "/")
                        q["host"]?.let { host ->
                            if (transport == "ws") add("headers", JsonObject().apply { addProperty("Host", host) })
                            else addProperty("host", host)
                        }
                    }
                })
            }
            "tuic" -> {
                q["congestion_control"]?.let { require(it in setOf("cubic", "new_reno", "bbr")); out.addProperty("congestion_control", it) }
                q["udp_relay_mode"]?.let { require(it in setOf("native", "quic")); out.addProperty("udp_relay_mode", it) }
                for (key in listOf("zero_rtt_handshake", "udp_over_stream")) bool(key)?.let { out.addProperty(key, it) }
                require(bool("udp_over_stream") != true || q["udp_relay_mode"] != "quic") { "UDP over stream несовместим с QUIC relay" }
            }
            "anytls" -> q["min_idle_session"]?.let { value ->
                val n = value.toIntOrNull(); require(n != null && n >= 0); out.addProperty("min_idle_session", n)
            }
        }
        for (key in listOf("heartbeat", "idle_session_check_interval", "idle_session_timeout")) q[key]?.let {
            CoreField(key, key, "", "duration").validate(com.google.gson.JsonPrimitive(it)); out.addProperty(key, it)
        }
        return out
    }
}
