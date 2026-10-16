package com.wireturn.app.data.kernel

/** One deterministic assignment for import, editing, activation and group measurements.
 * Explicit engines remain available to low-level adapters/tests, not as a user-facing choice.
 * A stored engine alone is not a preference anymore; native settings must remain effective.
 */
object VpnEnginePolicy {
    fun defaultEngine(protocol: String): String = when (protocol) {
        "vless", "hysteria2", "trojan", "xray-json" -> "xray"
        "snell", "shadowsocks", "tuic", "anytls", "naive", "awg", "wireguard", "trusttunnel" -> "sing-box"
        "mieru" -> "mieru"
        else -> "auto"
    }

    private fun nativeEngine(profile: VpnConfig): String? {
        val sb = (profile.options?.singBox?.size() ?: 0) > 0
        val xr = (profile.options?.xray?.size() ?: 0) > 0
        return when {
            sb && !xr -> "sing-box"
            xr && !sb -> "xray"
            sb && xr -> profile.selectedEngine.takeIf { it in setOf("sing-box", "xray") }
                // Preserve the old Auto preference when both sets of native options were stored.
                ?: if (com.wireturn.app.kernel.XrayVpnConfig.required(profile)) "xray" else "sing-box"
            else -> null
        }
    }

    fun assignedEngine(profile: VpnConfig): String {
        if (profile.isRawXray) return "xray"
        nativeEngine(profile)?.let { return it }
        val primary = defaultEngine(profile.protocol)
        if (primary !in setOf("xray", "sing-box")) return primary
        fun accepts(engine: String) = runCatching { profile.copy(engine = engine).validate() }.isSuccess
        if (accepts(primary)) return primary
        // Compatibility exceptions are decided from the configuration, never by network probes.
        // For example, the current Xray adapter cannot represent HY2 insecure or sub-5s hopping.
        return CoreCapabilities.engines(profile.protocol).firstOrNull {
            it != "auto" && it != primary && accepts(it)
        } ?: primary
    }

    fun apply(profile: VpnConfig): VpnConfig = profile.copy(engine = assignedEngine(profile))

    fun note(profile: VpnConfig): String? {
        if (profile.isRawXray) return "Xray применяет DNS, маршрутизацию и балансировщики из полного JSON. Локальные входы и журнал управляются приложением."
        val assigned = assignedEngine(profile)
        if (assigned == "xray") com.wireturn.app.kernel.XrayVpnConfig.compatibilityNote(profile)?.let { return it }
        if (nativeEngine(profile) != null) return "Ядро закреплено по сохранённым дополнительным настройкам профиля."
        if (assigned != defaultEngine(profile.protocol)) return "Ядро назначено по совместимости с параметрами ссылки."
        return null
    }
}
