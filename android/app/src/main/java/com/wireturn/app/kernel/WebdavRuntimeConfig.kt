package com.wireturn.app.kernel

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wireturn.app.data.kernel.WebdavConfig
import java.net.URI
import java.net.URLDecoder

/** JSON is a YAML subset; serialization preserves credentials and cannot inject YAML keys. */
object WebdavRuntimeConfig {
    fun build(config: WebdavConfig, listen: String, user: String, password: String, bootstrap: String): String {
        require(config.isValid()) { config.validationError().orEmpty() }
        require((user.isEmpty() && password.isEmpty()) ||
            (user.toByteArray().size in 1..255 && password.toByteArray().size in 1..255)) { "Некорректная авторизация локального SOCKS" }
        val json = JsonObject().apply {
            addProperty("mode", "client"); addProperty("socks-listen", listen)
            if (user.isNotEmpty()) { addProperty("socks-user", user); addProperty("socks-pass", password) }
            addProperty("enc", config.encrypt); addProperty("timeout", config.timeout)
            val dns = WebdavConfig.normalizeBootstrap(config.dns.ifBlank { bootstrap })
            if (dns.isNotEmpty()) addProperty("dns", dns)
            add("backends", JsonArray().apply {
                fun entry(url: String, login: String, secret: String) = JsonObject().apply {
                    addProperty("url", url); addProperty("login", login); addProperty("password", secret)
                }
                add(entry(config.webdav, config.login, config.password))
                config.backends.forEach { add(entry(it.url, it.login, it.password)) }
            })
            add("tuning", JsonObject().apply {
                addProperty("poll-min", config.pollMin); addProperty("poll-max", config.pollMax)
                addProperty("coalesce", config.coalesce); addProperty("chunk-size", config.chunkSize.toInt())
                addProperty("puts", config.puts.toInt()); addProperty("read-min", config.readMin.toInt()); addProperty("read-max", config.readMax.toInt())
            })
        }
        return Gson().toJson(json)
    }

    fun validateChain(active: Boolean, link: String) {
        if (!active) return
        val u = runCatching { URI(link) }.getOrNull()
        val values = u?.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val transports = values.filter { it.first in setOf("type", "network") }.map { it.second.lowercase() }
        require(u?.scheme?.lowercase() in setOf("vless", "trojan") &&
            transports.all { it in setOf("tcp", "raw", "ws", "http", "h2", "httpupgrade", "grpc", "xhttp", "splithttp") }) {
            "Цепочка WebDAV поддерживает только TCP: VLESS/Trojan без QUIC. Hysteria2 и TUIC через неё не работают."
        }
    }

    fun safeLog(line: String): String? = when {
        line.contains("server connected", true) -> "WebDAV: сервер выхода подключён"
        line.contains("server connection lost", true) -> "WebDAV: связь с сервером выхода потеряна"
        line.contains("server has not picked up the session", true) -> "WebDAV: сервер выхода не принял сеанс"
        line.contains("socks5 proxy listening on", true) -> "WebDAV: локальный прокси запущен; ожидание сервера выхода"
        line.contains("webdav backend(s) unreachable", true) -> "WebDAV: хранилища недоступны"
        line.contains("connection failed", true) -> "WebDAV: ошибка подключения к хранилищу — ${failureCategory(line)}"
        line.contains("fatal", true) || line.contains("panic", true) -> "WebDAV: ошибка ядра"
        else -> null
    }

    /** Emit categories only: native errors may contain URLs, credentials and document paths. */
    private fun failureCategory(line: String): String {
        val detail = line.lowercase(java.util.Locale.ROOT).substringAfter("connection failed:", "")
        return when {
            "no such host" in detail || "dns" in detail || "lookup " in detail -> "DNS"
            "x509" in detail || "tls:" in detail || "certificate" in detail -> "TLS"
            "timeout" in detail || "timed out" in detail || "deadline exceeded" in detail -> "таймаут"
            "connection refused" in detail || "network is unreachable" in detail || "connection reset" in detail -> "TCP"
            else -> Regex(": ([45][0-9]{2}) [a-z]").find(detail)?.groupValues?.get(1)?.let { "HTTP $it" }
                ?: "причина не определена"
        }
    }
}
