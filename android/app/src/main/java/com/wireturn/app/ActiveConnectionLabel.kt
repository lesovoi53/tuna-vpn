package com.wireturn.app

import com.wireturn.app.data.KernelConfig

/** Describes the running tunnel, never the internal SOCKS hop used by the router. */
internal object ActiveConnectionLabel {
    fun protocol(config: KernelConfig): String = when (config) {
        is KernelConfig.Vpn -> when (val name = config.config.protocol) {
            "mieru" -> "Mieru"
            "snell" -> "Snell"
            "hysteria2" -> "Hysteria2"
            "shadowsocks" -> "Shadowsocks"
            "trojan" -> "Trojan"
            "anytls" -> "AnyTLS"
            "naive" -> "NaiveProxy"
            else -> name.uppercase(java.util.Locale.ROOT)
        }
        is KernelConfig.Qwdtt -> "qWDTT"
        is KernelConfig.Webdav -> "WebDAV"
        is KernelConfig.MasterDns -> "MasterDNS"
        is KernelConfig.Turnable -> "TURNABLE"
        is KernelConfig.Olcrtc -> "olcRTC"
        is KernelConfig.FreeTurn -> "FreeTURN"
    }
    fun title(profileName: String?, fallback: String): String = profileName?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
