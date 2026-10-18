package com.wireturn.app.data.kernel

import com.google.gson.annotations.SerializedName
import com.wireturn.app.data.BootstrapDns
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class WebdavBackend(
    @SerializedName("label") val label: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("login") val login: String = "",
    @SerializedName("password") val password: String = ""
) {
    fun isValid(): Boolean = WebdavConfig.validStorageUrl(url) && login.isNotBlank() && password.isNotBlank() &&
        login.length <= 2048 && password.length <= 8192 && label.length <= 200
    fun sanitized() = copy(label = (label as String?).orEmpty(), url = (url as String?).orEmpty(),
        login = (login as String?).orEmpty(), password = (password as String?).orEmpty())
    fun toNestedUri(): String {
        require(isValid()) { "Некорректное хранилище WebDAV" }
        val u = URI(url)
        return "${if (u.scheme.equals("https", true)) "webdavs" else "webdav"}://${WebdavConfig.encode(login)}:${WebdavConfig.encode(password)}@${u.rawAuthority}${u.rawPath.orEmpty()}"
    }
    companion object {
        fun parseNestedUri(raw: String): WebdavBackend? = runCatching {
            val u = URI(raw)
            require(u.scheme.lowercase() in setOf("webdav", "webdavs") && u.host != null)
            val info = u.rawUserInfo ?: return null
            require(':' in info)
            // Split before decoding, exactly once. Encoded colons belong to credentials.
            val login = WebdavConfig.decode(info.substringBefore(':'), true)
            val password = WebdavConfig.decode(info.substringAfter(':'), true)
            val authority = u.rawAuthority.substringAfterLast('@')
            WebdavBackend(url = "${if (u.scheme.equals("webdavs", true)) "https" else "http"}://$authority${u.rawPath.orEmpty()}",
                login = login, password = password).takeIf { it.isValid() }
        }.getOrNull()
    }
}

/** DNS here resolves storage, independently of the DNS carried through the VPN. */
data class WebdavConfig(
    @SerializedName("webdav") val webdav: String = "",
    @SerializedName("login") val login: String = "",
    @SerializedName("password") val password: String = "",
    @SerializedName("dns") val dns: String = "",
    @SerializedName("backends") val backends: List<WebdavBackend> = emptyList(),
    @SerializedName("timeout") val timeout: String = "60s",
    @SerializedName("poll_max") val pollMax: String = "500ms",
    @SerializedName("poll_min") val pollMin: String = "200ms",
    @SerializedName("coalesce") val coalesce: String = "10ms",
    @SerializedName("chunk_size") val chunkSize: String = "131071",
    @SerializedName("puts") val puts: String = "8",
    @SerializedName("read_min") val readMin: String = "3",
    @SerializedName("read_max") val readMax: String = "8",
    @SerializedName("encrypt") val encrypt: Boolean = false
) {
    fun validationError(): String? = runCatching {
        require(WebdavBackend(url = webdav, login = login, password = password).isValid()) { "Укажите HTTP(S) URL хранилища без userinfo/параметров, логин и пароль" }
        require(backends.size <= MAX_BACKENDS && backends.all { it.isValid() }) { "Проверьте дополнительные хранилища (не более $MAX_BACKENDS)" }
        require(runCatching { normalizeBootstrap(dns) }.isSuccess) { "Bootstrap DNS: IP[:порт], udp://IP:порт или tcp://IP:порт" }
        val t = durationMillis(timeout)
        val lo = durationMillis(pollMin)
        val hi = durationMillis(pollMax)
        val co = durationMillis(coalesce)
        require(t != null && t in 1000.0..600000.0) { "Таймаут: от 1s до 10m" }
        require(lo != null && hi != null && lo in 1.0..60000.0 && hi in lo..60000.0) { "Опрос: 1ms–60s; минимум не больше максимума" }
        require(co != null && co in 0.0..1000.0) { "Coalesce: от 0s до 1s" }
        require(chunkSize.toIntOrNull() in 1024..4194304) { "Размер блока: 1024–4194304 байт" }
        require(puts.toIntOrNull() in 1..32 && readMin.toIntOrNull() in 1..32 && readMax.toIntOrNull() in 1..32 && readMin.toInt() <= readMax.toInt()) { "Параллелизм: 1–32; read-min не больше read-max" }
    }.exceptionOrNull()?.let { it.message ?: "Некорректный профиль WebDAV" }
    fun isValid(): Boolean = validationError() == null
    fun fillDefaults(): WebdavConfig = copy(
        webdav = (webdav as String?).orEmpty(), login = (login as String?).orEmpty(), password = (password as String?).orEmpty(), dns = (dns as String?).orEmpty(),
        timeout = (timeout as String?).orEmpty().ifBlank { "60s" }, pollMin = (pollMin as String?).orEmpty().ifBlank { "200ms" },
        pollMax = (pollMax as String?).orEmpty().ifBlank { "500ms" }, coalesce = (coalesce as String?).orEmpty().ifBlank { "10ms" },
        chunkSize = (chunkSize as String?).orEmpty().ifBlank { "131071" }, puts = (puts as String?).orEmpty().ifBlank { "8" },
        readMin = (readMin as String?).orEmpty().ifBlank { "3" }, readMax = (readMax as String?).orEmpty().ifBlank { "8" },
        backends = (backends as List<WebdavBackend?>?).orEmpty().map { it?.sanitized() ?: WebdavBackend() }
    )
    fun toUri(profileName: String? = null): String {
        require(isValid()) { validationError().orEmpty() }
        val base = WebdavBackend(url = webdav, login = login, password = password).toNestedUri()
        val values = mutableListOf("timeout" to timeout, "poll-min" to pollMin, "poll-max" to pollMax,
            "coalesce" to coalesce, "chunk-size" to chunkSize, "puts" to puts, "read-min" to readMin, "read-max" to readMax)
        if (encrypt) values += "enc" to "1"
        if (dns.isNotBlank()) values += "dns" to dns
        backends.forEach { values += "backend" to it.toNestedUri() }
        return base + "?" + values.joinToString("&") { (k, v) -> "$k=${encode(v)}" } +
            (profileName?.takeIf(String::isNotBlank)?.let { "#${encode(it)}" } ?: "")
    }
    companion object {
        const val MAX_BACKENDS = 8
        internal fun encode(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        internal fun decode(v: String, preservePlus: Boolean = false): String = URLDecoder.decode(if (preservePlus) v.replace("+", "%2B") else v, "UTF-8")
        fun validStorageUrl(value: String): Boolean = runCatching {
            val u = URI(value)
            value.length <= 8192 && u.scheme.lowercase() in setOf("http", "https") && !u.host.isNullOrBlank() &&
                u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && (u.port == -1 || u.port in 1..65535)
        }.getOrDefault(false)
        fun normalizeBootstrap(value: String): String {
            if (value.isBlank()) return ""
            val input = value.trim()
            if (input == "system") return input
            val transport = if (input.startsWith("tcp://")) "tcp" else "udp"
            val literal = input.removePrefix("tcp://").removePrefix("udp://")
            val parsed = requireNotNull(BootstrapDns.parse("tcp://$literal"))
            return parsed.xrayAddress().replaceFirst("tcp://", "$transport://")
        }
        fun durationMillis(value: String): Double? = runCatching {
            if (value == "0") return 0.0
            require(value.length <= 64)
            val parts = Regex("([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|μs|ms|s|m|h)").findAll(value).toList()
            require(parts.isNotEmpty() && parts.joinToString("") { it.value } == value)
            parts.sumOf { m -> m.groupValues[1].toDouble() * when (m.groupValues[2]) {
                "h" -> 3600000.0; "m" -> 60000.0; "s" -> 1000.0; "ms" -> 1.0; "ns" -> 0.000001; else -> 0.001
            } }.also { require(it.isFinite()) }
        }.getOrNull()
        @Suppress("UNUSED_PARAMETER")
        fun parse(uriStr: String, current: WebdavConfig = WebdavConfig()): WebdavConfig? = runCatching {
            require(uriStr.length <= 131072)
            val uri = URI(uriStr.trim())
            val primary = WebdavBackend.parseNestedUri(uriStr.trim()) ?: return null
            val pairs = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
            val allowed = setOf("timeout", "poll-min", "poll-max", "coalesce", "chunk-size", "puts", "read-min", "read-max", "enc", "dns", "backend")
            require(pairs.all { it.first in allowed })
            require(pairs.filter { it.first != "backend" }.map { it.first }.distinct().size == pairs.count { it.first != "backend" })
            val p = pairs.toMap()
            require(p["enc"] in setOf(null, "0", "1"))
            val extra = pairs.filter { it.first == "backend" }.map { (_, link) ->
                val u = URI(link)
                require(u.rawQuery == null && u.rawFragment == null)
                requireNotNull(WebdavBackend.parseNestedUri(link))
            }
            WebdavConfig(webdav = primary.url, login = primary.login, password = primary.password,
                dns = p["dns"].orEmpty(), backends = extra, timeout = p["timeout"] ?: "60s",
                pollMin = p["poll-min"] ?: "200ms", pollMax = p["poll-max"] ?: "500ms", coalesce = p["coalesce"] ?: "10ms",
                chunkSize = p["chunk-size"] ?: "131071", puts = p["puts"] ?: "8", readMin = p["read-min"] ?: "3", readMax = p["read-max"] ?: "8", encrypt = p["enc"] == "1")
                .takeIf { it.isValid() }
        }.getOrNull()
        fun formatHost(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("").ifBlank { "WebDAV" }
    }
}
