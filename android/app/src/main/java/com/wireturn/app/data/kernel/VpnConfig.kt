package com.wireturn.app.data.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** A standalone VPN endpoint. The source URI is private profile data, never a log label. */
data class VpnConfig(val uri: String = "", val engine: String = "auto", val options: VpnOptions? = null, val importedVariants: List<String> = emptyList(), val rawXrayJson: String? = null) {
    val isRawXray: Boolean get() = rawXrayJson != null
    val isXrayBalancer: Boolean get() = isRawXray && com.wireturn.app.kernel.RawXrayConfig.hasBalancer(rawXrayJson.orEmpty())
    val selectedEngine: String get() = (engine as String?).orEmpty().ifBlank { "auto" }
    val availableEngines: List<String> get() = CoreCapabilities.engines(protocol)
    fun sanitize(): VpnConfig {
        if (isRawXray) return copy(uri = "", engine = "xray", importedVariants = emptyList())
        val raw = (uri as String?).orEmpty().trim()
        val normalized = runCatching {
            val awg = if (AwgUri.recognizes(raw)) AwgUri.normalizeAll(raw).single() else raw
            TrustTunnelUri.normalize(awg)
        }.getOrDefault(raw)
        return VpnEnginePolicy.apply(copy(uri = normalized, engine = selectedEngine, importedVariants = (importedVariants as List<String>?).orEmpty().filter { it.length <= 32768 }.distinct().take(16)))
    }
    val usesXray: Boolean get() = CoreCapabilities.resolve(this) == "xray"
    val engineLabel: String get() = if (protocol == "mieru") "Mieru" else if (usesXray) "Xray" else "sing-box-extended"
    fun validate() {
        options?.validate()
        if (isRawXray) {
            require(selectedEngine in setOf("auto", "xray")) { "Полный JSON требует Xray" }
            com.wireturn.app.kernel.RawXrayConfig.validate(this)
            return
        }
        NativeOptions.validatePatch(CoreCapabilities.resolve(this), options?.forEngine(CoreCapabilities.resolve(this)) ?: JsonObject(), protocol)
        require(selectedEngine in availableEngines) { "Это ядро не поддерживает ${protocol.uppercase()}" }
        if (protocol == "mieru") MieruUri.validate(uri)
        else if (usesXray) {
            com.wireturn.app.kernel.XrayVpnConfig.validate(this)
            com.wireturn.app.kernel.VpnNativeValidation.xray(this, options?.forEngine("xray") ?: JsonObject())
        }
        else outbound()
        VpnFragmentation.validateFor(this)
    }
    fun isValid() = runCatching {
        validate()
        true
    }.getOrDefault(false)
    val protocol: String get() = if (isRawXray) "xray-json" else uri.substringBefore(':').lowercase().let { when (it) { "wg", "wireguard", "awg" -> if (AwgUri.isAmnezia(uri)) "awg" else "wireguard"; "tt" -> "trusttunnel"; "ss" -> "shadowsocks"; "hy2" -> "hysteria2"; "mierus" -> "mieru"; "naive+https" -> "naive"; else -> it } }
    val displayName: String get() = if (isRawXray) com.wireturn.app.kernel.RawXrayConfig.displayName(rawXrayJson.orEmpty()) else if (protocol == "mieru") MieruUri.name(uri) else runCatching { URI(uri).rawFragment?.let(::decode) }.getOrNull().orEmpty().ifBlank { protocol.uppercase() }
    val serverLabel: String get() = if (isRawXray) (if (isXrayBalancer) "Нативный балансировщик" else "Полная конфигурация") else runCatching { URI(uri).host.orEmpty() }.getOrDefault("")

    fun outbound(): JsonObject {
        require(!isRawXray) { "Полный JSON Xray запускается как конфигурация, а не отдельный outbound" }
        require(uri.length in 1..32768) { "Invalid VPN URI length" }
        if (protocol in setOf("awg", "wireguard", "trusttunnel")) {
            val base = if (protocol == "trusttunnel") TrustTunnelUri.outbound(uri) else AwgUri.parse(uri).endpoint
            val patch = options?.forEngine("sing-box") ?: JsonObject()
            NativeOptions.validatePatch("sing-box", patch, protocol)
            return NativeOptions.merge(base, patch).also { com.wireturn.app.kernel.VpnNativeValidation.extended(it) }
        }
        if (protocol == "naive") {
            val patch = options?.forEngine("sing-box") ?: JsonObject()
            NativeOptions.validatePatch("sing-box", patch, protocol)
            return NativeOptions.merge(NaiveUri.outbound(uri), patch).also {
                com.wireturn.app.kernel.VpnNativeValidation.extended(it)
            }
        }
        if (protocol in AdditionalVpnProtocols.protocols) {
            val base = AdditionalVpnProtocols.outbound(this)
            val patch = options?.forEngine("sing-box") ?: JsonObject()
            NativeOptions.validatePatch("sing-box", patch, protocol)
            return NativeOptions.merge(base, patch).also { com.wireturn.app.kernel.VpnNativeValidation.extended(it) }
        }
        val link = URI(uri)
        require(protocol in setOf("snell", "vless", "hysteria2")) { "Unsupported VPN protocol" }
        val host = requireNotNull(link.host) { "Missing server" }.removeSurrounding("[", "]")
        require(host.isNotBlank() && link.port in 1..65535) { "Invalid server or port" }
        require(link.path.isNullOrEmpty() || link.path == "/") { "Unexpected URI path" }
        val q = linkedMapOf<String, String>()
        for (part in link.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
            require('=' in part) { "Invalid query" }
            val key = decode(part.substringBefore('='))
            require(q.put(key, decode(part.substringAfter('='))) == null) { "Duplicate option: $key" }
        }
        require("fm" !in q) { "FinalMask (fm) не поддерживается закреплённым sing-box-extended; выберите Xray" }
        fun bool(key: String): Boolean? = q[key]?.let {
            when (it) { "true", "1" -> true; "false", "0" -> false; else -> error("Invalid boolean: $key") }
        }
        val out = JsonObject().apply {
            addProperty("type", protocol); addProperty("tag", "vpn")
            addProperty("server", host); addProperty("server_port", link.port)
        }
        val secret = link.rawUserInfo?.let(::decode).orEmpty()
        require(secret.isNotBlank()) { "Missing credentials" }
        val allowed: Set<String>
        when (protocol) {
            "snell" -> {
                allowed = setOf("version", "obfs-mode", "obfs-host", "reuse", "network", "userkey", "tfo", "udp-relay", "mode", "quic-proxy-mode", "quic_proxy_mode")
                require(q["version"] in setOf("4", "5", "6")) { "Supported Snell link versions: 4, 5, 6" }
                val obfs = q["obfs-mode"] ?: "none"
                require(obfs in setOf("none", "http", "tls")) { "Unsupported Snell obfuscation" }
                val v6 = q["version"] == "6"
                val quicModes = listOfNotNull(bool("quic-proxy-mode"), bool("quic_proxy_mode")).distinct()
                require(quicModes.size <= 1 && (v6 || quicModes.none { it })) { "QUIC Proxy требует Snell v6" }
                if (v6) quicModes.firstOrNull()?.let { out.addProperty("quic_proxy_mode", it) }
                out.addProperty("version", if (v6) 6 else 4); out.addProperty("psk", secret)
                if (v6) {
                    require(q["obfs-mode"] == null && q["obfs-host"] == null) { "Snell v6 использует mode вместо obfs" }
                    val mode = q["mode"] ?: "default"
                    require(mode in setOf("default", "unshaped", "unsafe-raw"))
                    out.addProperty("mode", mode)
                } else {
                    require(q["mode"] == null) { "mode требует Snell v6" }
                    out.addProperty("obfs_mode", obfs)
                    q["obfs-host"]?.let { require('\r' !in it && '\n' !in it); out.addProperty("obfs_host", it) }
                }
                q["userkey"]?.let { out.addProperty("userkey", it) }
                bool("reuse")?.let { out.addProperty("reuse", it) }
                bool("tfo")?.let { out.addProperty("tcp_fast_open", it) }
                bool("udp-relay")?.let {
                    if (!it) {
                        require(q["network"] in setOf(null, "auto", "tcp")) { "UDP disabled but UDP network selected" }
                        out.addProperty("network", "tcp")
                    }
                }
                q["network"]?.let { require(it in setOf("tcp", "udp", "auto")); if (it != "auto") out.addProperty("network", it) }
            }
            "vless" -> {
                allowed = setOf("security", "sni", "serverName", "fp", "pbk", "sid", "flow", "type", "path", "host", "serviceName", "alpn", "encryption", "allowInsecure", "mode", "extra", "ech", "echConfigList", "mux", "spx", "headerType", "fragment")
                require(q["headerType"] in setOf(null, "", "none")) { "TCP HTTP / KCP headerType требует Xray" }
                VpnFragmentation.validateFor(copy(engine = "sing-box"))
                val id = VlessId.canonical(secret)
                q["encryption"]?.let { com.wireturn.app.kernel.ExtendedVless.encryption(it); out.addProperty("encryption",it) }
                require(q["mux"] in setOf(null,"0")) { "Mux из ссылки использует Xray; выберите Xray или отключите Mux" }
                q["spx"]?.takeIf { it.isNotEmpty() }?.let {
                    val spider = URI(it)
                    require(it.startsWith('/') && !spider.isAbsolute && spider.rawAuthority == null && spider.rawQuery == null && spider.rawFragment == null) {
                        "Extended поддерживает путь SpiderX без query/fragment; для настроек crawler используйте Xray"
                    }
                    require(q["security"] == "reality") { "SpiderX требует Reality" }
                }
                out.addProperty("uuid", id)
                q["flow"]?.takeIf { it.isNotBlank() }?.let { require(it == "xtls-rprx-vision"); out.addProperty("flow", it) }
                val security = q["security"] ?: "none"
                require(security in setOf("none", "tls", "reality")) { "Unsupported VLESS security" }
                if (security != "none") out.add("tls", tls(q, security == "reality", bool("allowInsecure")))
                require(q["ech"] == null || q["echConfigList"] == null) { "Укажите только ech или echConfigList" }
                (q["ech"] ?: q["echConfigList"])?.let {
                    require(security == "tls") { "ECH требует TLS" }
                    out.getAsJsonObject("tls").add("ech",com.wireturn.app.kernel.ExtendedVless.ech(it))
                }
                val network = q["type"] ?: "tcp"
                require(network in setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "splithttp")) { "Unsupported VLESS transport: $network" }
                val xhttp = network in setOf("xhttp", "splithttp")
                require(xhttp || (q["mode"] == null && q["extra"] == null)) { "mode / extra требуют XHTTP" }
                if (xhttp) out.add("transport",com.wireturn.app.kernel.ExtendedVless.xhttp(q))
                else if (network != "tcp") out.add("transport", JsonObject().apply {
                    addProperty("type", network)
                    if (network == "httpupgrade") {
                        addProperty("path", q["path"] ?: "/"); q["host"]?.let { addProperty("host", it) }
                    } else if (network == "ws") {
                        addProperty("path", q["path"] ?: "/")
                        q["host"]?.let { add("headers", JsonObject().apply { addProperty("Host", it) }) }
                    } else addProperty("service_name", q["serviceName"] ?: "")
                })
            }
            else -> {
                allowed = setOf("sni", "insecure", "obfs", "obfs-password", "alpn", "security", "fp", "hop_interval", "hop_interval_max", "server_ports", "mport", "ech")
                require(q["server_ports"] == null || q["mport"] == null) { "Укажите только server_ports или mport" }
                (q["server_ports"] ?: q["mport"])?.let { ports ->
                    out.add("server_ports", JsonArray().apply { PortHopping.ranges(ports).forEach { add(if ('-' in it) it.replace('-', ':') else "$it:$it") } })
                }
                q["hop_interval_max"]?.let {
                    val max = PortHopping.seconds(it)
                    require(q["hop_interval"] != null && max >= PortHopping.seconds(q.getValue("hop_interval"))) { "Максимальный интервал меньше основного" }
                    out.addProperty("hop_interval_max", "${max}s")
                }
                q["hop_interval"]?.let {
                    PortHopping.seconds(it)
                    out.addProperty("hop_interval", if (it.endsWith("s")) it else "${it}s")
                }
                require(q["security"] in setOf(null, "tls")) { "Hysteria2 requires TLS" }
                out.addProperty("password", secret)
                // Shared links may carry a TCP fingerprint; Hysteria2 uses QUIC TLS.
                // Keep it in the source URI, but do not enable TCP uTLS for QUIC.
                out.add("tls", tls(q - "fp", false, bool("insecure")).apply {
                    q["ech"]?.takeIf { it.isNotBlank() }?.let { value ->
                        val bytes = java.util.Base64.getDecoder().decode(value)
                        require(bytes.size >= 6 && bytes.size <= 65537) { "Invalid ECH config list" }
                        val length = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
                        require(length == bytes.size - 2) { "Invalid ECH config list length" }
                        val pem = "-----BEGIN ECH CONFIGS-----\n" +
                            java.util.Base64.getEncoder().encodeToString(bytes).chunked(64).joinToString("\n") +
                            "\n-----END ECH CONFIGS-----\n"
                        add("ech", JsonObject().apply {
                            addProperty("enabled", true)
                            add("config", JsonArray().apply { add(pem) })
                        })
                    }
                })
                (q["obfs"] ?: q["obfs-password"]?.takeIf { it.isNotBlank() }?.let { "salamander" })?.let {
                    require(it == "salamander") { "Unsupported Hysteria2 obfuscation" }
                    val password = q["obfs-password"].orEmpty(); require(password.isNotBlank())
                    out.add("obfs", JsonObject().apply { addProperty("type", it); addProperty("password", password) })
                }
            }
        }
        require(q.keys.all { it in allowed }) { "Unsupported options: ${q.keys.filter { it !in allowed }.joinToString()}" }
        val patch = options?.forEngine("sing-box") ?: JsonObject()
        NativeOptions.validatePatch("sing-box", patch, protocol)
        return NativeOptions.merge(out, patch).also {
            if (protocol == "hysteria2" && it.has("realm")) {
                it.remove("server"); it.remove("server_port"); it.remove("server_ports")
                it.remove("hop_interval"); it.remove("hop_interval_max")
            }
            com.wireturn.app.kernel.VpnNativeValidation.extended(it)
        }
    }

    companion object {
        fun decode(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        fun isSnellConfigLine(text: String): Boolean =
            Regex("^[^=\\r\\n]+=[ \\t]*snell[ \\t]*,", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())

        fun parse(text: String): VpnConfig? = runCatching {
            val trimmed = VpnImportText.normalize(text.trim())
            // Names copied from clients often contain literal spaces after #.
            // Only normalize the display fragment, never credentials/query data.
            val input = if (AwgUri.isIni(trimmed)) AwgUri.normalizeAll(trimmed).single()
            else if ('#' in trimmed) trimmed.substringBefore('#') + "#" +
                trimmed.substringAfter('#').replace(" ", "%20") else trimmed
            var candidate = if (isSnellConfigLine(input)) snellLineToUri(input) else input
            if (AwgUri.recognizes(candidate)) candidate = AwgUri.normalizeAll(candidate).single()
            candidate = TrustTunnelUri.normalize(candidate)
            if (candidate.startsWith("vless://", true)) candidate = normalizeXhttpPadding(candidate)
            candidate = AdditionalVpnProtocols.normalize(candidate)
            val config = VpnConfig(candidate)
            require(config.isValid())
            // Preserve the server's Snell link version; outbound() selects compatible v4.
            VpnConfig(candidate).sanitize()
        }.getOrNull()

        private fun normalizeXhttpPadding(value: String): String {
            val link = URI(value)
            val parts = link.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)
            val padding = parts.filter { decode(it.substringBefore('=')) == "x_padding_bytes" }
            if (padding.isEmpty()) return value
            require(padding.size == 1)
            val type = parts.firstOrNull { decode(it.substringBefore('=')) == "type" }?.substringAfter('=')?.let(::decode)
            require(type in setOf("xhttp", "splithttp"))
            val amount = decode(padding.single().substringAfter('='))
            require(amount.matches(Regex("[0-9]+(-[0-9]+)?")))
            val extras = parts.filter { decode(it.substringBefore('=')) == "extra" }
            require(extras.size <= 1)
            val extra = extras.singleOrNull()?.let { com.google.gson.JsonParser.parseString(decode(it.substringAfter('='))).asJsonObject } ?: JsonObject()
            require(!extra.has("xPaddingBytes") || extra["xPaddingBytes"].asString == amount) { "Conflicting XHTTP padding" }
            extra.addProperty("xPaddingBytes", amount)
            val query = parts.filter { decode(it.substringBefore('=')) !in setOf("extra", "x_padding_bytes") } +
                ("extra=" + URLEncoder.encode(extra.toString(), "UTF-8").replace("+", "%20"))
            return value.substringBefore('?') + "?" + query.joinToString("&") + (link.rawFragment?.let { "#$it" } ?: "")
        }

        private fun snellLineToUri(text: String): String {
            fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            val name = text.substringBefore('=').trim()
            val parts = text.substringAfter('=').split(',').map(String::trim)
            require(parts.size >= 4 && parts[0].equals("snell", true))
            val host = parts[1].removeSurrounding("[", "]")
            val port = parts[2].toInt()
            require(host.isNotBlank() && port in 1..65535)
            val options = linkedMapOf<String, String>()
            for (part in parts.drop(3)) {
                require('=' in part)
                val key = part.substringBefore('=').trim().lowercase()
                require(options.put(key, part.substringAfter('=').trim()) == null) { "Duplicate option" }
            }
            val psk = requireNotNull(options.remove("psk"))
            val query = options.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            val authority = if (':' in host) "[$host]" else host
            return "snell://${encode(psk)}@$authority:$port/?$query#${encode(name)}"
        }
        private fun tls(q: Map<String, String>, reality: Boolean, insecure: Boolean?) = JsonObject().apply {
            addProperty("enabled", true)
            (q["sni"] ?: q["serverName"])?.let { addProperty("server_name", it) }
            insecure?.let { addProperty("insecure", it) }
            q["alpn"]?.let { add("alpn", JsonArray().apply { it.split(',').forEach { value -> add(value) } }) }
            if (reality || q["fp"] != null) add("utls", JsonObject().apply {
                addProperty("enabled", true); addProperty("fingerprint", q["fp"] ?: "chrome")
            })
            if (reality) add("reality", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("public_key", q["pbk"].also { require(!it.isNullOrBlank()) { "Missing Reality public key" } })
                addProperty("short_id", q["sid"] ?: "")
                q["spx"]?.takeIf { it.isNotEmpty() }?.let { addProperty("spider_x", it) }
            })
        }
    }
}
