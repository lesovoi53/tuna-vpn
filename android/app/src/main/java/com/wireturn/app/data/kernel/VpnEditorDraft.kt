package com.wireturn.app.data.kernel

import java.net.URI
import java.net.URLEncoder

data class VpnEditorField(val key: String, val label: String, val value: String, val secret: Boolean = false, val options: List<String> = emptyList(), val queryName: String? = null)

/** Replace only edited URI components. Preserve repeated query entries and opaque options. */
data class VpnEditorDraft(val original: String, val fields: List<VpnEditorField>) {
    val protocol: String get() = VpnConfig(original).protocol
    fun change(key: String, value: String) = copy(fields = fields.map { if (it.key == key) it.copy(value = value) else it })
    fun toUri(): String {
        val baseline = from(original)
        val changes = fields.filter { f -> baseline.fields.first { it.key == f.key }.value != f.value }.associate { it.key to it.value }
        if (changes.isEmpty()) return original
        val u = URI(original)
        val host = (changes["server"] ?: u.host).removeSurrounding("[", "]")
        require(host.isNotBlank() && host.none(Char::isWhitespace) && host.none { it in "/@?#" })
        val port = changes["port"] ?: u.port.takeIf { it >= 0 }?.toString().orEmpty()
        require(port.isEmpty() || port.toInt() in 1..65535)
        val credentials = if (protocol in setOf("awg", "wireguard")) "" else if (protocol in setOf("mieru", "tuic", "shadowsocks", "naive", "trusttunnel")) {
            val parts = u.rawUserInfo.split(':', limit = 2)
            (changes["username"]?.let(::encode) ?: parts[0]) + ":" +
                (changes["password"]?.let(::encode) ?: parts[1])
        } else changes["credential"]?.let(::encode) ?: u.rawUserInfo
        val query = u.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).mapIndexedNotNull { index, raw ->
            val value = changes["q:$index"] ?: return@mapIndexedNotNull raw
            if (value.isEmpty()) return@mapIndexedNotNull null
            raw.substringBefore('=') + "=" + encode(value)
        }.toMutableList()
        changes.filterKeys { it.startsWith("add:") }.forEach { (key, value) ->
            if (value.isNotBlank()) query += encode(key.removePrefix("add:")) + "=" + encode(value)
        }
        val result = u.scheme + "://" + (if (protocol in setOf("awg", "wireguard")) "" else credentials + "@") + (if (':' in host) "[$host]" else host) +
            (if (port.isEmpty()) "" else ":$port") + u.rawPath.orEmpty() +
            (if (query.isEmpty()) "" else "?" + query.joinToString("&")) +
            (u.rawFragment?.let { "#$it" } ?: "")
        if (protocol in setOf("awg", "wireguard")) {
            val edited = fields.filter { it.key in changes }
            val peerListChanged = edited.any { it.queryName == "peers" }
            val primaryChanged = edited.any { it.key in setOf("server", "port") ||
                it.queryName in setOf("public_key", "pre_shared_key", "allowed_ips", "persistent_keepalive_interval") }
            // When both representations are explicitly edited, ordinary validation must
            // reject a conflict instead of silently choosing one of the user's edits.
            if (peerListChanged != primaryChanged) return AwgUri.reconcileEditedPeers(result, peerListChanged)
        }
        return result
    }
    companion object {
        val manualProtocols = listOf(
            "vless" to "VLESS", "hysteria2" to "Hysteria2", "snell" to "Snell", "mieru" to "Mieru",
            "trojan" to "Trojan", "shadowsocks" to "Shadowsocks", "tuic" to "TUIC", "anytls" to "AnyTLS", "naive" to "NaiveProxy", "awg" to "AmneziaWG", "wireguard" to "WireGuard", "trusttunnel" to "TrustTunnel"
        )

        fun create(protocol: String): VpnEditorDraft {
            val template = when(protocol) {
                "vless" -> "vless://00000000-0000-4000-8000-000000000001@server.invalid:443?encryption=none&security=tls&type=tcp"
                "hysteria2" -> "hysteria2://password@server.invalid:443"
                "snell" -> "snell://password@server.invalid:443?version=4"
                "mieru" -> "mierus://user:password@server.invalid/?profile=default&port=443&protocol=TCP"
                "trojan" -> "trojan://password@server.invalid:443?security=tls&type=tcp"
                "shadowsocks" -> "ss://aes-256-gcm:password@server.invalid:443"
                "tuic" -> "tuic://00000000-0000-4000-8000-000000000001:password@server.invalid:443"
                "anytls" -> "anytls://password@server.invalid:443"
                "naive" -> "naive+https://user:password@server.invalid:443"
                "trusttunnel" -> "tt://user:password@server.invalid:443?security=tls"
                "awg", "wireguard" -> "wg://server.invalid:51820?private_key=&public_key=&local_address=10.0.0.2%2F32&enable_amnezia=${protocol == "awg"}"
                else -> error("Неизвестный редактор")
            }
            return from(template).change("server", "").let {
                if (protocol in setOf("mieru", "tuic", "shadowsocks", "naive", "trusttunnel")) it.change("password", "").let { d ->
                    if (protocol != "shadowsocks") d.change("username", "") else d
                } else it.change("credential", "")
            }
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun from(text: String): VpnEditorDraft {
            val u = URI(text)
            val protocol = VpnConfig(text).protocol
            require(protocol in (setOf("vless", "hysteria2", "snell", "mieru", "naive", "awg", "wireguard", "trusttunnel") + AdditionalVpnProtocols.protocols) && u.host != null)
            val fields = mutableListOf(VpnEditorField("server", "Сервер", u.host.removeSurrounding("[", "]")))
            if (protocol != "mieru") fields += VpnEditorField("port", "Порт", u.port.takeIf { it > 0 }?.toString().orEmpty())
            if (protocol in setOf("mieru", "tuic", "shadowsocks", "naive", "trusttunnel")) {
                val parts = u.rawUserInfo.split(':', limit = 2)
                fields += VpnEditorField("username", when(protocol) { "tuic" -> "UUID"; "shadowsocks" -> "Метод шифрования"; else -> "Имя пользователя" }, VpnConfig.decode(parts[0]))
                fields += VpnEditorField("password", "Пароль", VpnConfig.decode(parts[1]), true)
            } else if (protocol !in setOf("awg", "wireguard")) fields += VpnEditorField("credential", when(protocol) { "vless" -> "ID VLESS"; "snell" -> "PSK"; else -> "Пароль" }, VpnConfig.decode(u.rawUserInfo), true)
            val tlsLabels = linkedMapOf("security" to "Защита TLS", "sni" to "SNI", "alpn" to "ALPN через запятую", "insecure" to "Пропустить проверку TLS", "allowInsecure" to "Пропустить проверку TLS (Xray)", "allow_insecure" to "Пропустить проверку TLS")
            val labels = when (protocol) {
                "shadowsocks" -> linkedMapOf("type" to "Транспорт (tcp)")
                "trojan" -> LinkedHashMap(tlsLabels).apply { putAll(mapOf("type" to "Транспорт", "path" to "Путь", "host" to "HTTP Host", "serviceName" to "Сервис gRPC", "fp" to "TLS fingerprint")) }
                "tuic" -> LinkedHashMap(tlsLabels).apply { putAll(mapOf("congestion_control" to "Контроль перегрузки", "udp_relay_mode" to "Передача UDP", "zero_rtt_handshake" to "0-RTT", "udp_over_stream" to "UDP через поток", "heartbeat" to "Heartbeat (например 10s)")) }
                "anytls" -> LinkedHashMap(tlsLabels).apply { putAll(mapOf("idle_session_check_interval" to "Интервал проверки сессий", "idle_session_timeout" to "Таймаут неактивной сессии", "min_idle_session" to "Минимум неактивных сессий", "fp" to "TLS fingerprint")) }
                "naive" -> linkedMapOf("sni" to "SNI", "security" to "Защита TLS", "extra-headers" to "HTTP-заголовки (JSON)",
                    "udp-over-tcp" to "UDP over TCP (UoT)", "udp-over-tcp-version" to "Версия UoT (по умолчанию 2)")
                "awg", "wireguard" -> linkedMapOf<String, String>().apply { AwgUri.queryFields.sorted().forEach { put(it, it) } }
                "trusttunnel" -> LinkedHashMap(tlsLabels).apply { putAll(mapOf("client_random_prefix" to "Префикс client random", "use_cronet_https" to "Стек Chrome для HTTPS", "use_cronet_quic" to "Стек Chrome для QUIC", "force_quic" to "Только QUIC без отката на HTTPS", "health_check" to "Проверка работоспособности", "quic" to "QUIC вместо HTTPS", "network" to "Сеть: auto / tcp / udp", "congestion_controller" to "Контроллер перегрузки QUIC", "fp" to "Отпечаток TLS", "certificate" to "Сертификат CA (PEM)", "certificate_public_key_sha256" to "SHA-256 открытого ключа сертификата")) }
                "snell" -> linkedMapOf("quic-proxy-mode" to "QUIC Proxy (v6, совместимость с v5)", "mode" to "Режим Snell v6", "version" to "Версия Snell", "obfs-mode" to "Обфускация: none / http / tls", "obfs-host" to "Хост обфускации", "reuse" to "Повторное использование: true / false", "network" to "Сеть: auto / tcp / udp", "userkey" to "Ключ пользователя", "tfo" to "TCP Fast Open: true / false", "udp-relay" to "UDP: true / false")
                "vless" -> linkedMapOf("type" to "Транспорт: tcp / ws / grpc / httpupgrade / xhttp", "security" to "Защита: none / tls / reality", "encryption" to "Шифрование VLESS", "flow" to "Flow", "sni" to "SNI", "serverName" to "Имя TLS-сервера", "fp" to "TLS fingerprint", "pbk" to "Публичный ключ REALITY", "sid" to "Short ID", "spx" to "SpiderX", "path" to "Путь транспорта", "host" to "HTTP Host", "serviceName" to "Имя сервиса gRPC", "mode" to "Режим XHTTP", "extra" to "Дополнительные параметры XHTTP (JSON)", "ech" to "ECH: конфигурация или адрес запроса (Xray)", "mux" to "Mux: число соединений, 0 — выключено (Xray)", "alpn" to "ALPN через запятую", "allowInsecure" to "Пропустить проверку TLS: true / false")
                "hysteria2" -> linkedMapOf("sni" to "SNI", "obfs" to "Обфускация: salamander", "obfs-password" to "Пароль обфускации", "server_ports" to "Порты: 443,20000-20100", "hop_interval" to "Интервал смены порта, секунд", "hop_interval_max" to "Максимальный интервал смены порта, секунд", "ech" to "ECH ConfigList (Base64)", "alpn" to "ALPN через запятую", "insecure" to "Пропустить проверку TLS: true / false", "security" to "Защита (tls)", "fp" to "Fingerprint из ссылки (не применяется к QUIC)")
                else -> linkedMapOf("profile" to "Название профиля Mieru", "port" to "Порт или диапазон", "protocol" to "Транспорт: TCP / UDP", "multiplexing" to "Мультиплексирование", "traffic-pattern" to "Параметры трафика (Base64)", "mtu" to "MTU", "handshake-mode" to "Режим рукопожатия", "low-entropy-mode" to "Режим энтропии", "low-entropy-mask-rotation" to "Поворот маски")
            }
            if (protocol == "naive") {
                val importedKeys = u.rawQuery.orEmpty().split('&').map { VpnConfig.decode(it.substringBefore('=')) }.toSet()
                for (key in listOf("udp-over-tcp", "udp-over-tcp-version")) {
                    val alias = key.replace('-', '_')
                    if (alias in importedKeys) labels.remove(key)?.let { labels[alias] = it }
                }
            }
            val seen = mutableSetOf<String>()
            if (protocol == "vless") labels.putAll(linkedMapOf(
                "fragment" to "Фрагментация из ссылки: размер,задержка,tlshello",
                "headerType" to "Маскировка TCP / KCP", "headers" to "HTTP-заголовки (JSON)",
                "authority" to "gRPC authority", "multiMode" to "gRPC multiMode",
                "seed" to "Пароль маски KCP", "mtu" to "MTU KCP", "tti" to "TTI KCP, мс",
                "uplinkCapacity" to "KCP: пропускная способность отдачи", "downlinkCapacity" to "KCP: пропускная способность загрузки",
                "cwndMultiplier" to "KCP: множитель окна", "maxSendingWindow" to "KCP: максимальное окно",
                "fm" to "FinalMask (JSON Xray)", "pqv" to "REALITY ML-DSA-65 verification key",
                "pcs" to "Закреплённые SHA-256 сертификатов", "vcn" to "Имена для проверки сертификата"
            ))
            fun choices(key: String): List<String> = when (key) {
                "udp-over-tcp", "udp_over_tcp" -> listOf("", "true", "false")
                "udp-over-tcp-version", "udp_over_tcp_version" -> listOf("", "1", "2")
                "mtu" -> if (protocol == "mieru") listOf("", "0") + (1280..1500).map(Int::toString) else emptyList()
                "version" -> listOf("4", "5", "6")
                "use_cronet_https", "use_cronet_quic", "force_quic", "quic-proxy-mode", "quic_proxy_mode", "health_check", "quic", "enable_amnezia", "random_trailers", "disable_cookies", "reuse", "tfo", "udp-relay", "insecure", "allowInsecure", "allow_insecure", "zero_rtt_handshake", "udp_over_stream", "multiMode" -> listOf("", "true", "false")
                "network" -> listOf("", "auto", "tcp", "udp")
                "obfs-mode" -> listOf("", "none", "http", "tls")
                "obfs" -> listOf("", "salamander")
                "congestion_control", "congestion_controller", "quic_congestion_control" -> if (protocol == "trusttunnel") listOf("", "bbr", "bbr2", "cubic", "new_reno", "reno") else listOf("", "cubic", "new_reno", "bbr")
                "udp_relay_mode" -> listOf("", "native", "quic")
                "type" -> when(protocol) { "vless" -> listOf("") + VlessTransportOptions.transports; "shadowsocks" -> listOf("", "tcp"); "trojan" -> listOf("", "tcp", "ws", "grpc", "httpupgrade"); else -> listOf("", "tcp", "ws", "grpc", "httpupgrade", "xhttp") }
                "security" -> if (protocol in setOf("hysteria2", "trojan", "tuic", "anytls", "naive", "trusttunnel")) listOf("", "tls") else listOf("", "none", "tls", "reality")
                "mode" -> if (protocol == "snell") listOf("", "default", "unshaped", "unsafe-raw") else listOf("", "auto", "packet-up", "stream-up", "stream-one", "gun", "multi")
                "headerType" -> listOf("", "none", "http", "dns", "dtls", "srtp", "utp", "wechat", "wireguard")
                "flow" -> listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
                "protocol" -> listOf("TCP", "UDP")
                "multiplexing" -> listOf("") + listOf("DEFAULT", "OFF", "LOW", "MIDDLE", "HIGH").map { "MULTIPLEXING_$it" }
                "handshake-mode" -> listOf("", "HANDSHAKE_DEFAULT", "HANDSHAKE_STANDARD", "HANDSHAKE_NO_WAIT")
                "low-entropy-mode" -> listOf("") + MieruUri.lowEntropyModes
                "low-entropy-mask-rotation" -> listOf("") + MieruUri.rotations
                else -> emptyList()
            }
            u.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEachIndexed { index, raw ->
                val key = VpnConfig.decode(raw.substringBefore('=')); seen += key
                fields += VpnEditorField("q:$index", labels[key] ?: key, VpnConfig.decode(raw.substringAfter('=')), key in setOf("obfs-password", "userkey", "encryption", "private_key", "pre_shared_key", "header_protection_key", "peers"), choices(key), key)
            }
            labels.filterKeys { it !in seen && !(VpnConfig(text).usesXray && it in setOf("serverName", "allowInsecure")) }.forEach { (key, label) -> fields += VpnEditorField("add:$key", label, "", key in setOf("obfs-password", "userkey", "encryption", "private_key", "pre_shared_key", "header_protection_key", "peers"), choices(key), key) }
            return VpnEditorDraft(text, fields)
        }
    }
}
