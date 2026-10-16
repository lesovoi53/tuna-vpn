package com.wireturn.app.data.kernel

import java.net.URI

/** Curated editor/import choices. Parsing and loading stored profiles do not rewrite them. */
object VlessFingerprintChoices {
    fun forEngine(engine: String): List<String> = verified(engine)
    /** Editor catalog from the pinned native sources. Import replacements use only verified(). */
    fun verified(engine: String): List<String> = when (engine) {
        "sing-box", "xray" -> listOf("firefox", "safari")
        else -> emptyList()
    }
    fun supported(engine: String): List<String> = when (engine) {
        "xray" -> listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "randomizednoalpn", "hellofirefox_120", "hellofirefox_148", "hellochrome_120", "hellochrome_131", "hellochrome_133", "helloios_13", "helloios_14", "helloedge_106", "hellosafari_26_3", "hello360_11_0", "helloqq_11_1", "hellogolang", "hellorandomized", "hellorandomizedalpn", "hellorandomizednoalpn", "hellofirefox_auto", "hellofirefox_55", "hellofirefox_56", "hellofirefox_63", "hellofirefox_65", "hellofirefox_99", "hellofirefox_102", "hellofirefox_105", "hellochrome_auto", "hellochrome_58", "hellochrome_62", "hellochrome_70", "hellochrome_72", "hellochrome_83", "hellochrome_87", "hellochrome_96", "hellochrome_100", "hellochrome_102", "hellochrome_106_shuffle", "helloios_auto", "helloios_11_1", "helloios_12_1", "helloandroid_11_okhttp", "helloedge_85", "helloedge_auto", "hellosafari_16_0", "hellosafari_auto", "hello360_auto", "hello360_7_5", "helloqq_auto", "hellochrome_100_psk", "hellochrome_112_psk_shuf", "hellochrome_114_padding_psk_shuf", "hellochrome_115_pq", "hellochrome_115_pq_psk", "hellochrome_120_pq")
        "sing-box" -> listOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")
        else -> emptyList()
    }
    fun label(engine: String, value: String) = value +
        if (value in verified(engine)) " · проверен" else " · не проверен"


    fun isFingerprintField(protocol: String, path: String) = protocol in setOf("vless", "trojan", "anytls") && path in setOf(
        "tls.utls.fingerprint", "streamSettings.tlsSettings.fingerprint", "streamSettings.realitySettings.fingerprint"
    )

    private fun query(profile: VpnConfig): Map<String, String> = runCatching {
        URI(profile.uri).rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('='))
        }
    }.getOrDefault(emptyMap())

    private fun security(profile: VpnConfig): String? {
        val override = if (CoreCapabilities.resolve(profile) == "xray")
            profile.options?.forEngine("xray")?.let { NativeOptions.get(it, "streamSettings.security") } else null
        return override?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf { it.isNotEmpty() } ?: query(profile)["security"]
    }

    fun nativePath(profile: VpnConfig): String {
        val engine = CoreCapabilities.resolve(profile)
        return if (engine == "sing-box") "tls.utls.fingerprint"
            else if (security(profile) == "reality") "streamSettings.realitySettings.fingerprint"
            else "streamSettings.tlsSettings.fingerprint"
    }

    fun effective(profile: VpnConfig): String {
        val engine = CoreCapabilities.resolve(profile)
        val override = profile.options?.forEngine(engine)?.let { NativeOptions.get(it, nativePath(profile)) }
        return override?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: query(profile)["fp"] ?: "chrome"
    }

    fun warning(profile: VpnConfig): String? {
        if (profile.protocol !in setOf("vless", "trojan", "anytls")) return null
        val engine = CoreCapabilities.resolve(profile)
        if (profile.protocol == "vless" && effective(profile).trim().lowercase() in setOf("", "chrome", "random", "randomized"))
            return "При сохранении этот fingerprint будет заменён на проверенный firefox или safari."
        return if (effective(profile) in forEngine(engine)) null else
            "Этот fingerprint не проверен в наших тестах. Подключение может не работать; при необходимости выберите firefox или safari."
    }
}
