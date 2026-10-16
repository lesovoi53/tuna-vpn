package com.wireturn.app.kernel

import com.google.gson.JsonObject
import com.wireturn.app.data.kernel.NativeOptions
import com.wireturn.app.data.kernel.VpnConfig
import java.net.URI

/** Cross-field constraints before invoking the native parser. */
object VpnNativeValidation {
    private fun string(o: JsonObject, p: String) = NativeOptions.get(o, p)?.asString.orEmpty()
    private fun enabled(o: JsonObject, p: String) = NativeOptions.get(o, p)?.asBoolean == true
    fun extended(out: JsonObject) {
        val type = string(out, "type")
        val transport = string(out, "transport.type")
        if (type == "trusttunnel") {
            require(enabled(out, "tls.enabled")) { "TrustTunnel требует TLS" }
            val quic = enabled(out, "quic")
            val chrome = enabled(out, if (quic) "use_cronet_quic" else "use_cronet_https")
            require(!enabled(out, "force_quic") || (chrome && quic)) { "Force QUIC требует стек Chrome и QUIC" }
            require(!quic || !enabled(out,"tls.utls.enabled")) { "uTLS недоступен для QUIC" }
            if (chrome) {
                require(out.getAsJsonObject("tls").keySet().all { it in setOf("enabled", "server_name", "certificate", "insecure") } && !enabled(out,"tls.insecure") && string(out,"client_random_prefix").isEmpty()) {
                    "Стек Chrome поддерживает SNI и CA; удалите несовместимые дополнительные параметры TLS"
                }
            }
        }
        if (type == "snell") {
            val version = NativeOptions.get(out,"version")?.asInt
            require(version in setOf(4,6)) { "Неподдерживаемая версия Snell" }
            if (version == 6) require(!out.has("obfs_mode") && !out.has("obfs_host")) { "Snell v6 использует mode вместо obfs" }
            else require(!out.has("mode") && !enabled(out,"quic_proxy_mode")) { "mode и QUIC Proxy требуют Snell v6" }
        }
        if (type == "naive") {
            require(enabled(out, "tls.enabled")) { "NaiveProxy требует TLS" }
            require(out.getAsJsonObject("tls").keySet().all { it in setOf("enabled", "server_name", "certificate", "certificate_path", "ech") }) {
                "NaiveProxy использует Chromium TLS: параметры uTLS/Reality/ALPN не поддерживаются"
            }
            require(!out.has("transport") && !out.has("multiplex") && !enabled(out, "quic")) { "Этот профиль NaiveProxy использует HTTPS" }
            out["udp_over_tcp"]?.let(com.wireturn.app.data.kernel.NaiveUdpOverTcp::validate)
        }
        if (out.has("tls")) require(enabled(out, "tls.enabled")) { "Параметры TLS требуют включённого TLS/Reality в профиле" }
        out.getAsJsonObject("tls")?.getAsJsonObject("utls")?.let {
            if (it.has("fingerprint") && !it.has("enabled")) it.addProperty("enabled", true)
        }
        if (transport == "xhttp") {
            if (NativeOptions.get(out, "transport.x_padding_bytes") == null) out.getAsJsonObject("transport").addProperty("x_padding_bytes", "100-1000")
            out.getAsJsonObject("transport").getAsJsonObject("download")?.let {
                if (!it.has("x_padding_bytes")) it.addProperty("x_padding_bytes", "100-1000")
            }
        }
        if (enabled(out, "multiplex.enabled")) {
            require(type == "vless" && string(out, "flow") != "xtls-rprx-vision" && transport != "xhttp") { "SingMux несовместим с Vision/XHTTP; используйте параметры xmux для XHTTP" }
        }
        if (NativeOptions.get(out, "transport.download") != null || NativeOptions.get(out, "transport.xmux") != null)
            require(transport == "xhttp") { "Канал загрузки и xmux требуют XHTTP" }
        if (enabled(out, "tls.ech.enabled")) require(enabled(out, "tls.enabled") && !enabled(out, "tls.reality.enabled")) { "ECH требует TLS без Reality" }
        if (enabled(out, "tls.reality.support_x25519mlkem768"))
            require(enabled(out, "tls.reality.enabled")) { "Гибридный обмен ключами Reality требует security=reality в профиле" }
        if (type == "hysteria2" && out.has("obfs")) {
            require(string(out, "obfs.type") in listOf("salamander", "gecko") && string(out, "obfs.password").isNotBlank()) { "Обфускация Hysteria2 требует типа и пароля" }
            val low = NativeOptions.get(out, "obfs.min_packet_size")?.asInt ?: 0
            val high = NativeOptions.get(out, "obfs.max_packet_size")?.asInt ?: 0
            require(high == 0 || high >= low) { "Максимальный пакет Gecko меньше минимального" }
        }
        if (out.has("realm")) {
            require(type == "hysteria2" && string(out, "realm.server_url").startsWith("https://") && string(out, "realm.realm_id").isNotBlank()) { "Realm: укажите HTTPS URL и ID" }
            require(NativeOptions.get(out, "realm.stun_servers")?.let { it.isJsonArray && it.asJsonArray.size() > 0 } == true) { "Realm: укажите STUN-серверы" }
            require(NativeOptions.get(out, "realm.ip_version")?.asInt in listOf(null, 0, 4, 6)) { "Realm: версия IP — 0, 4 или 6" }
        }
        if (NativeOptions.get(out, "tls.min_version") != null && NativeOptions.get(out, "tls.max_version") != null)
            require(string(out, "tls.min_version") <= string(out, "tls.max_version")) { "Минимальная версия TLS больше максимальной" }
    }
    fun xray(profile: VpnConfig, patch: JsonObject) {
        val query = URI(profile.uri).rawQuery.orEmpty().split('&').associate { VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('=', "")) }
        val network = string(patch, "streamSettings.network").ifEmpty { query["type"].orEmpty() }
        val security = string(patch, "streamSettings.security").ifEmpty { query["security"] ?: "tls" }
        val transport = com.wireturn.app.data.kernel.VlessTransportOptions.canonicalTransport(network)
        if (profile.protocol == "vless" && security == "reality") require(transport in setOf("tcp", "grpc", "xhttp", "splithttp")) {
            "Xray REALITY поддерживает только TCP, XHTTP и gRPC"
        }
        for ((field, types) in listOf("tcpSettings" to setOf("tcp"), "wsSettings" to setOf("ws"),
            "grpcSettings" to setOf("grpc"), "kcpSettings" to setOf("kcp"), "httpupgradeSettings" to setOf("httpupgrade"))) {
            if (NativeOptions.get(patch, "streamSettings.$field") != null) require(transport in types) { "$field несовместим с выбранным транспортом" }
        }
        if (NativeOptions.get(patch, "streamSettings.tlsSettings") != null) require(security == "tls") { "tlsSettings требует security=tls" }
        if (NativeOptions.get(patch, "streamSettings.realitySettings") != null) require(security == "reality") { "realitySettings требует security=reality" }
        if (enabled(patch, "mux.enabled")) require(profile.protocol == "vless" && query["flow"]?.startsWith("xtls-rprx-vision") != true && network !in setOf("xhttp", "splithttp")) { "Xray Mux несовместим с Vision/XHTTP/Hysteria2" }
        if (NativeOptions.get(patch, "streamSettings.xhttpSettings") != null) require(network in setOf("xhttp", "splithttp")) { "Параметры XHTTP требуют транспорта XHTTP" }
    }
}
