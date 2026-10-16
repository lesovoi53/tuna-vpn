package com.wireturn.app.data.kernel

import com.google.gson.JsonParser

/** URI options implemented by the pinned Xray wrapper, with cross-field loss prevention. */
object VlessTransportOptions {
    val additionalKeys = setOf("fragment", "headerType", "headers", "authority", "multiMode", "seed", "mtu", "tti",
        "uplinkCapacity", "downlinkCapacity", "cwndMultiplier", "maxSendingWindow", "pqv", "mldsa65Verify",
        "pcs", "pinnedPeerCertSha256", "vcn", "verifyPeerCertByName")
    val transports = listOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "splithttp", "kcp", "mkcp")
    fun canonicalTransport(value: String?) = when (value) { null, "", "raw" -> "tcp"; "mkcp" -> "kcp"; else -> value }

    fun validate(q: Map<String, String>) {
        val type = canonicalTransport(q["type"])
        require(type in transports) { "Транспорт VLESS $type не поддержан закреплёнными ядрами" }
        val xhttp = type in setOf("xhttp", "splithttp")
        val kcp = type == "kcp"
        val security = q["security"] ?: "tls"
        require(q["sni"] == null || q["serverName"] == null || q["sni"] == q["serverName"]) { "Противоречивые SNI и serverName" }
        if (security == "reality") require(type in setOf("tcp", "grpc", "xhttp", "splithttp")) {
            "Xray REALITY поддерживает только TCP, XHTTP и gRPC; выберите совместимый транспорт или ядро"
        }
        val header = q["headerType"].orEmpty()
        if (header !in setOf("", "none")) require((type == "tcp" && header == "http") ||
            (kcp && header in setOf("dns", "dtls", "srtp", "utp", "wechat", "wireguard"))) { "headerType несовместим с транспортом" }
        q["headers"]?.takeIf { it.isNotBlank() }?.let { raw ->
            require(type in setOf("ws", "httpupgrade", "xhttp", "splithttp")) { "headers требуют WebSocket, HTTPUpgrade или XHTTP" }
            val headers = JsonParser.parseString(raw)
            require(headers.isJsonObject && headers.asJsonObject.entrySet().all { (key, value) ->
                key.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                    '\r' !in value.asString && '\n' !in value.asString
            }) { "headers: нужен JSON-объект строковых HTTP-заголовков" }
        }
        if (q["extra"] != null) require(xhttp) { "extra требует XHTTP" }
        q["mode"]?.takeIf { it.isNotBlank() }?.let {
            require(if (type == "grpc") it in setOf("gun", "multi") else xhttp && it in setOf("auto", "packet-up", "stream-up", "stream-one")) { "mode несовместим с транспортом" }
        }
        for (key in listOf("authority", "multiMode", "serviceName")) if (!q[key].isNullOrBlank()) require(type == "grpc") { "$key требует gRPC" }
        q["multiMode"]?.let { require(it in setOf("", "0", "1", "false", "true")) { "multiMode: true / false" } }
        if (q["mode"] in setOf("gun", "multi") && q["multiMode"] != null) require(
            (q["mode"] == "multi") == (q["multiMode"] in setOf("true", "1"))) { "Противоречивые mode и multiMode gRPC" }
        for (key in listOf("mtu", "tti", "uplinkCapacity", "downlinkCapacity", "cwndMultiplier", "maxSendingWindow")) q[key]?.let {
            require(kcp) { "$key требует KCP" }
            val number = it.toLongOrNull()
            require(number != null && number in 1..4294967295L) { "$key: положительное 32-битное число" }
            if (key == "mtu") require(number in 21..65535) { "MTU KCP: 21–65535" }
            if (key == "tti") require(number in 10..1000) { "TTI KCP: 10–1000" }
        }
        q["seed"]?.takeIf { it.isNotEmpty() }?.let {
            require(kcp) { "seed требует KCP" }
            require(header in setOf("", "none") && q["fm"] == null) { "seed и headerType/fm KCP конфликтуют: иначе ядро потеряет seed" }
        }
        for ((primary, alias) in listOf("mldsa65Verify" to "pqv", "pinnedPeerCertSha256" to "pcs", "verifyPeerCertByName" to "vcn")) {
            require(q[primary] == null || q[alias] == null || q[primary] == q[alias]) { "Противоречивые $primary и $alias" }
            val value = (q[primary] ?: q[alias])?.takeIf { it.isNotEmpty() } ?: continue
            require(security == if (primary == "mldsa65Verify") "reality" else "tls") { "$primary несовместим с защитой соединения" }
            if (primary == "mldsa65Verify") require(runCatching { java.util.Base64.getUrlDecoder().decode(value).size == 1952 }.getOrDefault(false)) { "Некорректный ключ ML-DSA-65" }
            if (primary == "pinnedPeerCertSha256") require(value.split(',').all { it.trim().replace(":", "").matches(Regex("[0-9a-fA-F]{64}")) }) { "SHA-256 сертификата: 64 hex-символа" }
        }
    }
}
