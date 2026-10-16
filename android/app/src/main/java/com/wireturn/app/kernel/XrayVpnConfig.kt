package com.wireturn.app.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.kernel.VpnConfig
import java.net.URI
import com.wireturn.app.data.kernel.VlessId
import com.wireturn.app.data.kernel.VlessTransportOptions
import com.wireturn.app.data.kernel.VpnFragmentation

/** Standalone advanced VLESS uses the bundled Xray link parser, without a TURN hop. */
object XrayVpnConfig {
    fun compatibilityNote(profile: VpnConfig): String? = runCatching {
        if (profile.isRawXray) return@runCatching null
        val q = parameters(profile.uri)
        if (q.containsKey("support-x25519mlkem768"))
            return@runCatching "support-x25519mlkem768 сохранён как параметр совместимости. В Xray обмен ключами REALITY определяется ядром и fingerprint, а не этим флагом."
        if (q["type"] !in setOf("xhttp", "splithttp")) return@runCatching null
        val extra = q["extra"]?.let(::parseExtra)
        if (extra?.getAsJsonObject("xmux")?.has("cMaxLifetimeMs") == true)
            "Xray 26.9.9 не поддерживает xmux.cMaxLifetimeMs: поле сохранено в ссылке, но не применяется."
        else null
    }.getOrNull()
    private fun parameters(uri: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (part in URI(uri).rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
            require('=' in part)
            val key = VpnConfig.decode(part.substringBefore('='))
            require(result.put(key, VpnConfig.decode(part.substringAfter('='))) == null) { "Duplicate VLESS option" }
        }
        return result
    }

    fun required(profile: VpnConfig): Boolean = profile.protocol == "vless" && runCatching {
        val q = parameters(profile.uri)
        q["type"] in setOf("xhttp", "splithttp") || q["encryption"].orEmpty().let { it.isNotEmpty() && it != "none" } ||
            q.keys.any { it in setOf("ech", "echConfigList", "echForceQuery", "mux", "fm") || it in VlessTransportOptions.additionalKeys } ||
            profile.options?.fragmentation?.mode?.let { it != "off" } == true
    }.getOrDefault(false)

    fun validate(profile: VpnConfig) {
        if (profile.isRawXray) { RawXrayConfig.validate(profile); return }
        if (profile.protocol == "trojan") {
            val base = com.wireturn.app.data.kernel.AdditionalVpnProtocols.outbound(profile)
            require(base.getAsJsonObject("tls")["insecure"]?.asBoolean != true) { "Xray: отключение проверки TLS не поддержано адаптером" }
            require(parameters(profile.uri).keys.none { it in setOf("insecure", "allow_insecure") }) { "Для Xray используйте allowInsecure=0" }
            return
        }
        if (profile.protocol == "hysteria2") {
            profile.copy(options = null).outbound() // Validate the common HY2 contract before adapting it to Xray.
            val q = parameters(profile.uri)
            require(q["insecure"] !in setOf("true", "1")) { "Xray HY2: отключение проверки сертификата не поддержано адаптером; выберите sing-box-extended" }
            if (q["server_ports"] != null || q["mport"] != null) {
                listOfNotNull(q["hop_interval"], q["hop_interval_max"]).forEach {
                    require(com.wireturn.app.data.kernel.PortHopping.seconds(it) % 1.0 == 0.0 && com.wireturn.app.data.kernel.PortHopping.seconds(it) >= 5) { "Xray: интервал смены порта — целое число секунд, не менее 5" }
                }
            }
            return
        }
        require(profile.protocol == "vless" && profile.uri.length in 1..32768)
        val link = URI(profile.uri)
        require(!link.host.isNullOrBlank() && link.port in 1..65535)
        require(link.path.isNullOrEmpty() || link.path == "/")
        val user = VpnConfig.decode(requireNotNull(link.rawUserInfo))
        VlessId.canonical(user)
        val q = parameters(profile.uri)
        val allowed = setOf("security", "sni", "serverName", "allowInsecure", "fp", "pbk", "sid", "spx", "flow", "type", "path", "host", "serviceName", "alpn", "encryption", "mode", "extra", "ech", "echConfigList", "echForceQuery", "mux", "fm", "support-x25519mlkem768") + VlessTransportOptions.additionalKeys
        require(q.keys.all { it in allowed }) { "Неподдерживаемые параметры VLESS: ${q.keys.filter { it !in allowed }.joinToString()}" }
        q["support-x25519mlkem768"]?.let {
            require(it in setOf("true", "false", "1", "0") && q["security"] == "reality") {
                "support-x25519mlkem768: требуется boolean и security=reality"
            }
        }
        VlessTransportOptions.validate(q)
        q["fm"]?.let { raw ->
            // The wrapper silently ignores malformed fm. Reject it here instead of losing settings.
            // Preserve the original JSON in the link; the pinned Xray parser owns its field schema.
            com.google.gson.stream.JsonReader(java.io.StringReader(raw)).use { reader ->
                reader.strictness = com.google.gson.Strictness.STRICT
                require(JsonParser.parseReader(reader).isJsonObject &&
                    reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) { "FinalMask (fm) должен быть JSON-объектом" }
            }
        }
        require(q["allowInsecure"] in setOf(null, "false", "0")) { "Xray: отключение проверки TLS не поддержано адаптером" }
        require(q["ech"] == null || q["echConfigList"] == null) { "Укажите только ech или echConfigList" }
        (q["ech"] ?: q["echConfigList"])?.let {
            require(q["security"] == "tls" && it.isNotBlank() && it.length <= 100000 && '\n' !in it && '\r' !in it) { "ECH требует TLS и конфигурацию ECH" }
        }
        require(q["echForceQuery"] == null) { "echForceQuery отсутствует в закреплённом ядре Xray; удалите этот параметр" }
        q["mux"]?.let {
            val mux = it.toIntOrNull()
            require(mux != null && mux in 0..1024) { "Mux: 0–1024 соединений" }
            require(mux == 0 || (q["flow"]?.startsWith("xtls-rprx-vision") != true && q["type"] !in setOf("xhttp", "splithttp"))) { "Mux нельзя сочетать с Vision или XHTTP" }
        }
        require(q["security"] in setOf(null, "none", "tls", "reality"))
        if (q["security"] == "reality") require(!q["pbk"].isNullOrBlank())
        q["encryption"]?.let { encryption ->
            // Only syntax/size here. NativeConfigCheck validates the actual algorithm,
            // keys and padding with the packaged core before imports are committed.
            require(encryption.isNotBlank() && encryption.length <= 32768 &&
                encryption.none { it.isWhitespace() || it.isISOControl() }) { "Некорректное значение VLESS encryption" }
        }
        q["extra"]?.let(::parseExtra)
        q["flow"]?.let { require(it in setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) { "Неподдерживаемый VLESS flow" } }
        VpnFragmentation.validateFor(profile)
    }

    private fun parseExtra(raw: String): JsonObject =
        com.google.gson.stream.JsonReader(java.io.StringReader(raw)).use { reader ->
            reader.strictness = com.google.gson.Strictness.STRICT
            val value = JsonParser.parseReader(reader)
            require(value.isJsonObject && reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) {
                "XHTTP extra должен быть одним JSON-объектом"
            }
            value.asJsonObject
        }

    fun generate(profile: VpnConfig, listen: String, user: String, password: String, allowNoAuth: Boolean, bootstrapDns: String = ""): JsonObject {
        if (profile.isRawXray) {
            com.wireturn.app.data.BootstrapDns.parse(bootstrapDns)
            return RawXrayConfig.generate(profile, listen, user, password, allowNoAuth)
        }
        validate(profile)
        val patch = VpnFragmentation.xrayPatch(profile, profile.options?.forEngine("xray") ?: JsonObject())
        com.wireturn.app.data.kernel.NativeOptions.validatePatch("xray", patch, profile.protocol)
        VpnNativeValidation.xray(profile, patch)
        val address = URI("socks://$listen")
        require(address.host in setOf("127.0.0.1", "[::1]", "::1") && address.port in 1..65535)
        require(allowNoAuth || user.isNotBlank() && password.isNotBlank())
        // JSON is also YAML, accepted by vless-client's -config loader. Secrets stay
        // inside application-private storage rather than process arguments.
        return JsonObject().apply {
            val q = parameters(profile.uri)
            val link = URI(profile.uri)
            val params = q.toMutableMap()
            params.remove("fragment") // Translated losslessly into the explicit native patch.
            if (profile.protocol == "vless" && q["type"] in setOf("raw", "mkcp")) params["type"] = VlessTransportOptions.canonicalTransport(q["type"])
            if (q["type"] in setOf("xhttp", "splithttp")) params["extra"]?.let { raw ->
                val extra = parseExtra(raw)
                // The pinned wrapper flattens extra. Native Xray's nested-extra contract gives
                // the outer transport keys precedence; apply that precedence before flattening.
                // Original profile URI remains untouched; only the executable copy is adapted.
                for (key in listOf("path", "host", "mode")) q[key]?.let { extra.addProperty(key, it) }
                // Not a field in pinned Xray's XmuxConfig. Our strict native adapter rejects it.
                // Preserve it for export/editing and disclose the limitation in compatibilityNote.
                extra.getAsJsonObject("xmux")?.remove("cMaxLifetimeMs")
                params["extra"] = extra.toString()
            }
            params.remove("mux")
            params.remove("support-x25519mlkem768") // Clash hint, not an Xray REALITY setting.
            params.remove("serverName")?.let { if (params["sni"] == null) params["sni"] = it }
            params.remove("allowInsecure")
            if (profile.protocol == "hysteria2") {
                val ports = params.remove("server_ports") ?: params.remove("mport")
                val min = params.remove("hop_interval")
                val max = params.remove("hop_interval_max")
                if (ports != null) {
                    fun seconds(v: String?, fallback: Int) = v?.let { com.wireturn.app.data.kernel.PortHopping.seconds(it).toInt() } ?: fallback
                    val low = seconds(min, 5)
                    val high = seconds(max, if (min == null) 10 else low)
                    params["fm"] = JsonObject().apply {
                        add("udp", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply { addProperty("type", "udphop"); add("settings", JsonObject().apply {
                                addProperty("mode", "intervalRemote")
                                addProperty("remotePorts", com.wireturn.app.data.kernel.PortHopping.ranges(ports).joinToString(","))
                                addProperty("interval", "$low-$high")
                            }) })
                            params["obfs-password"]?.takeIf { it.isNotBlank() }?.let { secret ->
                                add(JsonObject().apply { addProperty("type", "salamander"); add("settings", JsonObject().apply { addProperty("password", secret) }) })
                            }
                        })
                    }.toString()
                }
                // Wrapper defaults to no obfuscation unless this explicit option is present.
                if (!params["obfs-password"].isNullOrBlank() && params["obfs"] == null) params["obfs"] = "salamander"
            }
            fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            // The CLI wrapper does not percent-decode credentials. Give it the canonical
            // equivalent ID, keeping the original (including Unicode) in the saved profile.
            val runtimeBase = if (profile.protocol == "vless")
                "vless://${VlessId.canonical(VpnConfig.decode(link.rawUserInfo))}@${link.rawAuthority.substringAfterLast('@')}${link.rawPath.orEmpty()}"
                else profile.uri.substringBefore('?').substringBefore('#')
            val normalized = runtimeBase +
                (if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }) +
                (link.rawFragment?.let { "#$it" } ?: "")
            // The pinned CLI link parser keeps percent escapes in Trojan credentials.
            // Supply decoded structured settings, without changing the stored URI or binary.
            val credentials = JsonObject()
            if (profile.protocol == "trojan") credentials.add("settings", JsonObject().apply {
                add("servers", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", link.host.removeSurrounding("[", "]"))
                        addProperty("port", link.port)
                        addProperty("password", VpnConfig.decode(link.rawUserInfo))
                    })
                })
            })
            add("outbound_patch", com.wireturn.app.data.kernel.NativeOptions.merge(credentials, patch))
            addProperty("link", normalized)
            q["mux"]?.let { addProperty("mux", it.toInt()) }
            addProperty("listen", listen)
            com.wireturn.app.data.BootstrapDns.parse(bootstrapDns) // Applied via TUNA_BOOTSTRAP_DNS, not tunnel DNS.
            addProperty("dns", "8.8.8.8,1.1.1.1")
            if (!allowNoAuth) { addProperty("proxy_user", user); addProperty("proxy_pass", password) }
        }
    }
}
