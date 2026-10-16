package com.wireturn.app.data.kernel

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import com.google.gson.annotations.SerializedName

// Original qWDTT URI fields plus TUNA optional mode settings. The server protocol and
// authenticated userspace WireGuard SOCKS path are unchanged. Local UDP listen ports
// from shared links never replace this device's own listener settings.
data class QwdttConfig(
    @SerializedName("peer") val peer: String = "",
    @SerializedName("hashes") val vkHashes: String = "",
    @SerializedName("password") val password: String = "",
    @SerializedName("workers") val workers: Int = 18,
    @SerializedName("obfs") val obfsMode: String = "video",
    @SerializedName("turn_tcp") val turnTcp: Boolean = false,
    @SerializedName("go_dns") val goDns: String = "yandex",
    // -notls: direct mode, RTP-obfs AEAD without DTLS on top of TURN. Only works if the server was
    // itself started with -listen-direct - it's a compatibility switch, not a speed/privacy knob,
    // so it must match whatever the peer server actually expects.
    @SerializedName("no_tls") val noTls: Boolean = false,
    // Maps to "-captcha-mode wv": skips the binary's own automatic captcha-solving chain and
    // always requests our WebView (see CoreService.handleQwdttLog's "selected" branch). Trades
    // away the cases auto-solving would have handled silently for a captcha prompt every time.
    @SerializedName("manual_captcha") val manualCaptcha: Boolean = false,
    val anonymousPath: String = "vkcalls",
    val accountAuth: Boolean = false
) {
    val effectiveWorkers: Int get() = if (accountAuth) workers.coerceIn(1, 4) else (workers.coerceIn(9, 108) / 9) * 9
    fun isValid(): Boolean = isImportable() && (vkHashes.isNotBlank() || accountAuth)
    fun isImportable(): Boolean = runCatching {
        val endpoint = URI("https://$peer")
        endpoint.host != null && endpoint.port in 1..65535 && endpoint.rawUserInfo == null &&
            endpoint.rawPath.isNullOrEmpty() && endpoint.rawQuery == null && endpoint.rawFragment == null &&
            password.isNotBlank() && password.length <= 256 && workers in 1..108 &&
            obfsMode in listOf("audio", "video") &&
            anonymousPath in listOf("vkcalls", "legacy") &&
            hashList.size <= 6 && hashList.distinct().size == hashList.size &&
            hashList.all { it.length in 16..2000 && it.none(Char::isWhitespace) }
    }.getOrDefault(false)
    val hashList: List<String> get() = vkHashes.split(',').map(String::trim).filter(String::isNotEmpty)

    fun addressLabel(): String = FreeTurnConfig.maskPeer(peer)

    fun automaticTurnModes() = copy(anonymousPath = "vkcalls", manualCaptcha = false)

    fun sanitize(): QwdttConfig = copy(
        peer = (peer as Any?)?.toString()?.trim()?.take(500) ?: "",
        vkHashes = (vkHashes as Any?)?.toString()?.trim()?.take(2000) ?: "",
        password = (password as Any?)?.toString()?.trim()?.take(256) ?: "",
        workers = workers.coerceIn(1, 108),
        anonymousPath = (anonymousPath as String?) ?: "vkcalls",
        obfsMode = if (obfsMode == "audio") "audio" else "video",
        goDns = ((goDns as Any?)?.toString()?.trim()?.take(4096)).let { if (it.isNullOrBlank()) "yandex" else it }
    ).automaticTurnModes()

    // Legacy clients ignore the additional mode keys. Account tokens are never exported.
    fun toUri(profileName: String? = null): String {
        val fields = linkedMapOf("peer" to peer, "hashes" to vkHashes, "workers" to workers.toString(),
            "pass" to password, "anonymousPath" to anonymousPath,
            "obfs" to obfsMode, "turn_tcp" to turnTcp.toString(), "no_tls" to noTls.toString(),
            "manual_captcha" to manualCaptcha.toString(), "go_dns" to goDns, "accountAuth" to accountAuth.toString())
        if (!profileName.isNullOrBlank()) fields["name"] = profileName
        return "qwdtt://config?" + fields.entries.joinToString("&") { (k,v) -> "$k=${encode(v)}" }
    }

    companion object {
        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        fun parse(url: String, current: QwdttConfig = QwdttConfig()): QwdttConfig? = runCatching {
            if (url.length > 32768) return null
            val text = url.trim()
            if (text.startsWith("wdtt://", true) && !text.startsWith("wdtt://connect?", true)) {
                val parts = text.substringAfter("://").split(":")
                if (parts.size < 6) return null
                return current.copy(peer = "${parts[0]}:${parts[1]}", password = parts[4],
                    vkHashes = parts.drop(5).joinToString(":"))
                    .takeIf { it.isImportable() }?.automaticTurnModes()
            }
            val normalized = if (text.startsWith("qwdtt:config", true))
                text.replaceFirst("qwdtt:", "qwdtt://", true) else text
            val uri = URI(normalized.substringBefore('#'))
            val modernWdtt = uri.scheme.equals("wdtt", true) && uri.host == "connect"
            if (!modernWdtt && !(uri.scheme.equals("qwdtt", true) && uri.host == "config")) return null
            if (uri.rawUserInfo != null || uri.port != -1 || !uri.path.isNullOrEmpty() || uri.fragment != null) return null
            val values = linkedMapOf<String, String>()
            for (pair in uri.rawQuery.orEmpty().replace("&amp;", "&").split('&').filter(String::isNotEmpty)) {
                if ('=' !in pair || values.put(decode(pair.substringBefore('=')), decode(pair.substringAfter('='))) != null) return null
            }
            if (modernWdtt && values["v"] != "1") return null
            var peer = if (modernWdtt) {
                val host = values["host"] ?: return null
                "${if (host.contains(':') && !host.startsWith('[')) "[$host]" else host}:${values["dtls"] ?: return null}"
            } else values["peer"] ?: return null
            if (!modernWdtt && URI("https://$peer").port == -1) {
                val port = values["dtls_port"] ?: values["server_port"] ?: return null
                peer += ":$port"
            }
            fun bool(key: String, fallback: Boolean): Boolean = values[key]?.toBooleanStrict() ?: fallback
            current.copy(peer = peer, vkHashes = values["hashes"].orEmpty(),
                password = values["pass"] ?: values["password"] ?: return null,
                workers = (values["workers"] ?: values["max_workers"])?.toInt() ?: current.workers,
                anonymousPath = values["anonymousPath"] ?: "vkcalls",
                obfsMode = values["obfs"] ?: current.obfsMode, turnTcp = bool("turn_tcp", current.turnTcp),
                noTls = bool("no_tls", current.noTls), manualCaptcha = bool("manual_captcha", current.manualCaptcha),
                goDns = values["go_dns"] ?: current.goDns, accountAuth = bool("accountAuth", false)).takeIf { it.isImportable() }?.automaticTurnModes()
        }.getOrNull()
    }
}
