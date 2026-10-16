package com.wireturn.app.domain

/** Recognition only; each endpoint still goes through its strict protocol parser. */
internal object SubscriptionTextFormat {
    private val schemes = listOf("freeturn", "olcrtc", "olconnect", "turnable", "webdav", "webdavs",
        "qwdtt", "wdtt", "mierus", "snell", "vless", "hy2", "hysteria2", "trojan", "ss", "tuic",
        "anytls", "naive+https", "stormdns", "tuna", "wireturn", "wt", "wg", "awg", "wireguard", "vpn", "tt", "trusttunnel")

    fun recognizes(text: String): Boolean = schemes.any { text.contains("$it://", ignoreCase = true) } ||
        Regex("(?m)^\\s*[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text) ||
        text.contains("qwdtt:config", ignoreCase = true) || text.contains("#name:", ignoreCase = true)
}
