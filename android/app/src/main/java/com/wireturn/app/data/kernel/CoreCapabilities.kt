package com.wireturn.app.data.kernel

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

data class CoreField(val path: String, val label: String, val group: String, val kind: String = "string",
    val choices: List<String> = emptyList(), val hint: String = "", val min: Long = 0, val max: Long = Int.MAX_VALUE.toLong()) {
    fun parse(text: String): JsonElement? {
        if (text.isBlank()) return null
        val value = when (kind) {
            "bool", "int", "json", "uot" -> JsonParser.parseString(text)
            else -> JsonPrimitive(text)
        }
        validate(value)
        return value
    }
    fun validate(value: JsonElement) {
        if (kind == "uot") { NaiveUdpOverTcp.validate(value); return }
        val valid = when (kind) {
            "bool" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
            "int" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().toLongOrNull()?.let { it in min..max } == true
            "json" -> value.isJsonObject || value.isJsonArray
            "duration" -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.matches(Regex("[0-9]+(\\.[0-9]+)?(ms|s|m|h)"))
            else -> value.isJsonPrimitive && value.asJsonPrimitive.isString
        }
        require(valid && (choices.isEmpty() || value.asString in choices)) { "$label: неверное значение" }
    }
}

/** This is the adapter contract for the pinned binaries, not an upstream feature wishlist. */
object CoreCapabilities {
    private val autoResolutions = linkedMapOf<String, String>()
    const val EXTENDED_VERSION = "1.14.1-extended-2.7.2"
    const val XRAY_VERSION = "26.9.9"
    fun engines(protocol: String) = when (protocol) {
        "xray-json" -> listOf("auto", "xray")
        "vless", "hysteria2", "trojan" -> listOf("auto", "sing-box", "xray")
        "snell", "shadowsocks", "tuic", "anytls", "naive", "awg", "wireguard", "trusttunnel" -> listOf("auto", "sing-box")
        "mieru" -> listOf("auto", "mieru")
        else -> listOf("auto")
    }
    fun resolve(profile: VpnConfig): String {
        if (profile.isRawXray) return "xray"
        if (profile.protocol == "mieru") return "mieru"
        if (profile.selectedEngine != "auto") return profile.selectedEngine
        val key = profile.uri + "|" + profile.options.toString()
        synchronized(autoResolutions) { autoResolutions[key] }?.let { return it }
        val resolved = VpnEnginePolicy.assignedEngine(profile)
        synchronized(autoResolutions) {
            if (autoResolutions.size >= 64) autoResolutions.clear()
            autoResolutions[key] = resolved
        }
        return resolved
    }
    private fun preferred(profile: VpnConfig): String {
        val sb = profile.options?.singBox?.size() ?: 0
        val xr = profile.options?.xray?.size() ?: 0
        if (sb > 0 && xr == 0) return "sing-box"
        if (xr > 0 && sb == 0) return "xray"
        return if (com.wireturn.app.kernel.XrayVpnConfig.required(profile)) "xray" else "sing-box"
    }
    /** Validate explicit candidates: never recursively resolve Auto while checking compatibility. */
    fun compatibleEngines(profile: VpnConfig): List<String> {
        val engines = engines(profile.protocol).filter { it != "auto" }
        if (engines.isEmpty()) return emptyList()
        val sb = profile.options?.singBox?.size() ?: 0
        val xr = profile.options?.xray?.size() ?: 0
        return engines.filter { engine ->
            (profile.selectedEngine == "auto" || profile.selectedEngine == engine) &&
                // A one-engine native patch has no equivalent on the other core; never discard it.
                !(sb > 0 && xr == 0 && engine != "sing-box") &&
                !(xr > 0 && sb == 0 && engine != "xray") &&
                runCatching { profile.copy(engine = engine).validate() }.isSuccess
        }.sortedBy { if (it == preferred(profile)) 0 else 1 }
    }
    fun compatibility(profile: VpnConfig): Map<String, String?> = engines(profile.protocol).filter { it != "auto" }.associateWith { engine ->
        runCatching { profile.copy(engine = engine).validate() }.exceptionOrNull()?.let { it.message ?: "Параметры несовместимы с ядром" }
    }
    fun report(): String = """
        Подключения TUNA VPN

        VPN — ядра по умолчанию
        VLESS, Hysteria2, Trojan — Xray $XRAY_VERSION.
        Snell, Shadowsocks, TUIC, AnyTLS, NaiveProxy — sing-box-extended $EXTENDED_VERSION.
        Mieru — отдельное ядро Mieru 3.37.

        Обход БС
        qWDTT, WebDAV, MasterDNS / StormDNS.

        Ядро выбирается автоматически с учётом параметров подключения.
        Маршрутизация и DNS по умолчанию — Xray. VPN-интерфейс Android — HEV.
    """.trimIndent()

    fun fields(engine: String, protocol: String): List<CoreField> {
        if (protocol == "mieru") return emptyList()
        if (protocol == "naive") return listOf(
            CoreField("connect_timeout", "Таймаут подключения", "Подключение", "duration"),
            CoreField("tls.server_name", "SNI", "TLS"),
            CoreField("tls.certificate", "Сертификаты CA (JSON-массив PEM)", "TLS", "json"),
            CoreField("extra_headers", "Дополнительные HTTP-заголовки", "NaiveProxy", "json"),
            CoreField("stream_receive_window", "Окно приёма (например 4 MB)", "NaiveProxy"),
            CoreField("udp_over_tcp", "UDP over TCP", "NaiveProxy", "uot",
                hint = "true / false; {\"enabled\":true,\"version\":2}")
        )
        val result = mutableListOf<CoreField>()
        fun add(path: String, label: String, group: String, kind: String = "string", choices: List<String> = emptyList(), hint: String = "", min: Long = 0, max: Long = Int.MAX_VALUE.toLong()) {
            result += CoreField(path, label, group, kind, choices, hint, min, max)
        }
        if (engine == "sing-box") {
            add("connect_timeout", "Таймаут подключения", "Подключение", "duration")
            add("tcp_fast_open", "TCP Fast Open", "Подключение", "bool")
            add("disable_tcp_keep_alive", "Отключить TCP keep-alive", "Подключение", "bool")
            add("tcp_keep_alive", "Начальный период keep-alive", "Подключение", "duration")
            add("tcp_keep_alive_interval", "Интервал keep-alive", "Подключение", "duration")
            add("udp_fragment", "Фрагментация UDP", "Подключение", "bool")
            if (protocol !in setOf("snell", "shadowsocks", "awg", "wireguard")) {
                add("tls.server_name", "SNI", "TLS")
                add("tls.alpn", "ALPN (JSON-массив)", "TLS", "json")
                add("tls.min_version", "Минимальная версия TLS", "TLS", choices = listOf("1.0", "1.1", "1.2", "1.3"))
                add("tls.max_version", "Максимальная версия TLS", "TLS", choices = listOf("1.0", "1.1", "1.2", "1.3"))
                add("tls.cipher_suites", "Шифры TLS (JSON-массив)", "TLS", "json")
                add("tls.curve_preferences", "Кривые TLS (JSON-массив)", "TLS", "json")
                add("tls.certificate", "Сертификаты CA (JSON-массив PEM)", "TLS", "json")
                add("tls.certificate_public_key_sha256", "SHA256 открытого ключа (JSON-массив)", "TLS", "json")
                add("tls.client_certificate", "Клиентский сертификат (JSON-массив PEM)", "TLS", "json")
                add("tls.client_key", "Закрытый ключ клиента (JSON-массив PEM)", "TLS", "json")
                add("tls.handshake_timeout", "Таймаут TLS", "TLS", "duration")
                add("tls.ech", "ECH (нативный JSON)", "TLS", "json")
                if (protocol == "vless") {
                    add("tls.reality.support_x25519mlkem768", "Reality: гибридный обмен ключами", "Reality", "bool",
                        hint = "X25519MLKEM768 для новых серверов Reality. По умолчанию выключен; старым серверам может требоваться выключенное значение. Обновлённые Chrome, Firefox и Safari поддерживают этот обмен.")
                    add("tls.fragment", "Фрагментация TLS", "TLS", "bool")
                    add("tls.fragment_fallback_delay", "Задержка фрагментации", "TLS", "duration")
                    add("tls.record_fragment", "Фрагментация TLS records", "TLS", "bool")
                    add("tls.utls.fingerprint", "uTLS fingerprint", "TLS")
                    add("tls.utls.enabled", "Включить uTLS", "TLS", "bool")
                    add("multiplex.enabled", "Включить SingMux", "Multiplex", "bool")
                    add("multiplex.protocol", "Протокол SingMux", "Multiplex", choices = listOf("h2mux", "smux", "yamux"))
                    for ((key, title) in listOf("max_connections" to "Максимум соединений", "min_streams" to "Минимум потоков", "max_streams" to "Максимум потоков")) add("multiplex.$key", title, "Multiplex", "int")
                    add("multiplex.padding", "Padding", "Multiplex", "bool")
                    add("multiplex.brutal.enabled", "Brutal (нужна поддержка сервера)", "Multiplex", "bool")
                    add("multiplex.brutal.up_mbps", "Brutal: отдача, Мбит/с", "Multiplex", "int")
                    add("multiplex.brutal.down_mbps", "Brutal: загрузка, Мбит/с", "Multiplex", "int")
                    add("packet_encoding", "Кодирование пакетов", "Протокол", choices = listOf("packetaddr", "xudp"))
                    add("transport", "Транспорт (нативный JSON)", "Транспорт", "json")
                    add("transport.download", "XHTTP: канал загрузки (нативный JSON)", "Транспорт", "json", hint = "Для XHTTP; server/server_port/tls и параметры транспорта extended")
                    add("transport.xmux", "XHTTP xmux (нативный JSON)", "Транспорт", "json")
                }
            }
            if (protocol == "hysteria2") {
                add("obfs.type", "Тип обфускации", "Hysteria2", choices = listOf("salamander", "gecko"))
                add("obfs.password", "Пароль обфускации", "Hysteria2")
                add("obfs.min_packet_size", "Gecko: минимальный пакет", "Hysteria2", "int")
                add("obfs.max_packet_size", "Gecko: максимальный пакет", "Hysteria2", "int")
                add("bbr_profile", "Профиль BBR", "Hysteria2", choices = listOf("standard", "conservative", "aggressive"))
                add("up_mbps", "Отдача Brutal, Мбит/с", "Hysteria2", "int")
                add("down_mbps", "Загрузка Brutal, Мбит/с", "Hysteria2", "int")
                add("idle_timeout", "QUIC: таймаут простоя", "QUIC", "duration")
                add("keep_alive_period", "QUIC: keep-alive", "QUIC", "duration")
                for ((key, label) in listOf("stream_receive_window" to "Окно потока, байт", "connection_receive_window" to "Окно соединения, байт", "max_concurrent_streams" to "Максимум потоков", "initial_packet_size" to "Начальный размер пакета")) add(key, label, "QUIC", "int")
                add("disable_path_mtu_discovery", "Отключить Path MTU discovery", "QUIC", "bool")
                add("realm.server_url", "URL сервера Realm", "Realm")
                add("realm.token", "Токен Realm", "Realm")
                add("realm.realm_id", "ID Realm", "Realm")
                add("realm.stun_servers", "STUN-серверы (JSON-массив)", "Realm", "json")
                add("realm.ip_version", "IP Realm: 0/4/6", "Realm", "int", min = 0, max = 6)
                add("realm.port_mapping.enabled", "Проброс порта Realm", "Realm", "bool")
                add("realm.port_mapping.timeout", "Таймаут проброса", "Realm", "duration")
                add("realm.port_mapping.lifetime", "Время жизни проброса", "Realm", "duration")
            }
        } else if (engine == "xray") {
            add("streamSettings.sockopt.tcpFastOpen", "TCP Fast Open", "Подключение", "bool")
            add("streamSettings.sockopt.tcpKeepAliveIdle", "Начальный keep-alive, секунд", "Подключение", "int")
            add("streamSettings.sockopt.tcpKeepAliveInterval", "Интервал keep-alive, секунд", "Подключение", "int")
            add("streamSettings.tlsSettings", "TLS (нативный JSON Xray)", "TLS", "json")
            add("streamSettings.realitySettings", "Reality (нативный JSON Xray)", "TLS", "json")
            add("streamSettings.tlsSettings.pinnedPeerCertSha256", "SHA256 сертификатов через запятую", "TLS")
            add("streamSettings.tlsSettings.alpn", "ALPN (JSON-массив)", "TLS", "json")
            add("streamSettings.tlsSettings.serverName", "SNI", "TLS")
            add("streamSettings.tlsSettings.minVersion", "Минимальная версия TLS", "TLS", choices = listOf("1.0", "1.1", "1.2", "1.3"))
            add("streamSettings.tlsSettings.maxVersion", "Максимальная версия TLS", "TLS", choices = listOf("1.0", "1.1", "1.2", "1.3"))
            add("streamSettings.tlsSettings.cipherSuites", "Шифры TLS через двоеточие", "TLS")
            add("streamSettings.tlsSettings.curvePreferences", "Кривые TLS (JSON-массив)", "TLS", "json")
            add("streamSettings.tlsSettings.certificates", "Сертификаты (JSON Xray)", "TLS", "json")
            add("streamSettings.tlsSettings.enableSessionResumption", "Возобновление TLS-сессии", "TLS", "bool")
            add("streamSettings.sockopt.tcpUserTimeout", "Таймаут TCP, мс", "Подключение", "int")
            add("streamSettings.tlsSettings.fingerprint", "Fingerprint", "TLS")
            add("streamSettings.tlsSettings.echConfigList", "ECH ConfigList", "TLS")
            if (protocol == "vless") {
                add("streamSettings.finalmask", "FinalMask (нативный JSON Xray)", "Фрагментация", "json")
                add("streamSettings.tcpSettings", "TCP (нативный JSON)", "Транспорт", "json")
                add("streamSettings.kcpSettings", "KCP (нативный JSON)", "Транспорт", "json")
                add("streamSettings.wsSettings", "WebSocket (нативный JSON)", "Транспорт", "json")
                add("streamSettings.grpcSettings", "gRPC (нативный JSON)", "Транспорт", "json")
                add("streamSettings.httpupgradeSettings", "HTTPUpgrade (нативный JSON)", "Транспорт", "json")
                add("streamSettings.realitySettings.fingerprint", "Fingerprint Reality", "TLS")
                add("streamSettings.xhttpSettings", "XHTTP (нативный JSON)", "Транспорт", "json")
                add("streamSettings.xhttpSettings.downloadSettings", "XHTTP: канал загрузки", "Транспорт", "json")
                add("streamSettings.xhttpSettings.xmux", "XHTTP xmux", "Транспорт", "json")
                add("mux.enabled", "Включить Xray Mux", "Multiplex", "bool")
                add("mux.concurrency", "Число потоков Xray Mux", "Multiplex", "int", min = 1, max = 1024)
                add("mux.xudpConcurrency", "Число потоков XUDP", "Multiplex", "int")
                add("mux.xudpProxyUDP443", "UDP/443", "Multiplex", choices = listOf("reject", "allow", "skip"))
            } else if (protocol == "hysteria2") {
                add("streamSettings.hysteriaSettings.udpIdleTimeout", "UDP idle timeout, секунд (2–600)", "QUIC", "int", min = 2, max = 600)
                add("streamSettings.finalmask", "FinalMask (нативный JSON Xray)", "QUIC", "json")
                add("streamSettings.finalmask.quicParams.congestion", "Контроль перегрузки", "QUIC", choices = listOf("bbr", "brutal"))
                add("streamSettings.finalmask.quicParams.bbrProfile", "Профиль BBR", "QUIC", choices = listOf("standard", "conservative", "aggressive"))
                add("streamSettings.finalmask.quicParams.brutalUp", "Brutal: отдача (например 20mbps)", "QUIC")
                add("streamSettings.finalmask.quicParams.brutalDown", "Brutal: загрузка (например 100mbps)", "QUIC")
                for (key in listOf("initStreamReceiveWindow", "maxStreamReceiveWindow", "initConnectionReceiveWindow", "maxConnectionReceiveWindow", "maxIdleTimeout", "keepAlivePeriod", "maxIncomingStreams"))
                    add("streamSettings.finalmask.quicParams.$key", key, "QUIC", "int")
                add("streamSettings.finalmask.quicParams.disablePathMTUDiscovery", "Отключить Path MTU discovery", "QUIC", "bool")
            }
        }
        return result
    }
}
