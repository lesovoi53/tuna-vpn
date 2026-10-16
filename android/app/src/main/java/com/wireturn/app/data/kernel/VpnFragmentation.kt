package com.wireturn.app.data.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig

/** Stable user-facing modes. A null profile setting inherits; off explicitly opts out. */
data class VpnFragmentation(val mode: String = "off", val length: String = "100-200", val interval: String = "0-1") {
    fun validate() {
        require(mode in modes) { "Неизвестный режим фрагментации" }
        range(length, 1, 16384, "Размер фрагмента")
        range(interval, 0, 1000, "Задержка фрагментации")
    }

    fun xrayMask(): JsonObject {
        validate()
        require(mode != "off")
        return JsonObject().apply {
            addProperty("type", "fragment")
            add("settings", JsonObject().apply {
                addProperty("packets", if (mode == "tcp") "1" else "tlshello")
                addProperty("length", length)
                // In pinned Xray one zero delay coalesces TLS records into one write.
                // Two entries force separate writes even when both requested delays are zero.
                if (mode == "both") add("delays", JsonArray().apply { add(interval); add(interval) })
                else addProperty("delay", if (mode == "tls") "0" else interval)
            })
        }
    }

    companion object {
        val modes = listOf("off", "tls", "tcp", "both")
        fun label(mode: String) = when (mode) {
            "off" -> "Выключена"; "tls" -> "Фрагментация записей TLS"
            "tcp" -> "Сегментация TCP"; "both" -> "Записи TLS и сегментация TCP"
            else -> "Из ссылки / общих настроек"
        }
        private fun range(value: String, min: Int, max: Int, label: String) {
            require(value.matches(Regex("[0-9]+(-[0-9]+)?"))) { "$label: число или диапазон" }
            val parts = value.split('-').map { it.toIntOrNull() }
            val low = parts.first(); val high = parts.last()
            require(low != null && high != null && low in min..max && high in low..max) { "$label: допустимо $min–$max" }
        }
        fun fromUri(uri: String): VpnFragmentation? {
            val values = URI(uri).rawQuery.orEmpty().split('&').filter {
                VpnConfig.decode(it.substringBefore('=')) == "fragment"
            }
            if (values.isEmpty()) return null
            require(values.size == 1) { "Повторяющийся параметр fragment" }
            val value = VpnConfig.decode(values.single().substringAfter('=', ""))
            if (value in setOf("", "0", "false", "off")) return VpnFragmentation()
            val parts = value.split(',')
            require(parts.size == 3 && parts[2] == "tlshello") { "fragment: ожидается размер,задержка,tlshello" }
            return VpnFragmentation("both", parts[0], parts[1]).also { it.validate() }
        }

        fun effective(profile: VpnConfig): VpnFragmentation? =
            profile.options?.fragmentation ?: if (profile.protocol == "vless") fromUri(profile.uri) else null

        private fun parameters(profile: VpnConfig) = URI(profile.uri).rawQuery.orEmpty().split('&').associate {
            VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('=', ""))
        }

        fun compatibilityReason(profile: VpnConfig): String? {
            if (profile.protocol != "vless") return "Фрагментация доступна для VLESS TLS/REALITY"
            val q = parameters(profile)
            val patch = profile.options?.forEngine("xray") ?: JsonObject()
            val security = NativeOptions.get(patch, "streamSettings.security")?.asString ?: q["security"] ?: "tls"
            val transport = NativeOptions.get(patch, "streamSettings.network")?.asString ?: q["type"] ?: "tcp"
            if (security !in setOf("tls", "reality")) return "Фрагментация ClientHello требует TLS или REALITY"
            if (transport !in setOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "splithttp"))
                return "Фрагментация ClientHello требует транспорта поверх TCP, не KCP/QUIC"
            if (security == "reality" && transport !in setOf("tcp", "raw", "grpc", "xhttp", "splithttp"))
                return "Фрагментация REALITY через Xray доступна только с TCP, XHTTP или gRPC"
            if ((profile.options?.singBox?.size() ?: 0) > 0 && (profile.options?.xray?.size() ?: 0) == 0)
                return "Точные размеры фрагментов требуют Xray; профиль закреплён за настройками sing-box"
            return null
        }

        fun validateFor(profile: VpnConfig) {
            if (profile.protocol == "vless" && profile.selectedEngine != "sing-box") {
                normalizeFragmentMasks(combinedMask(profile, profile.options?.forEngine("xray") ?: JsonObject()))
            }
            val setting = effective(profile) ?: return
            setting.validate()
            if (setting.mode == "off") return
            require(profile.selectedEngine != "sing-box") { "Три режима с точными размерами фрагментов требуют Xray" }
            require(compatibilityReason(profile) == null) { compatibilityReason(profile).orEmpty() }
            if (profile.options?.fragmentation == null) {
                val q = parameters(profile)
                val raw = q["fm"]?.let { com.google.gson.JsonParser.parseString(it).asJsonObject }
                require(raw?.getAsJsonArray("tcp")?.none { it.asJsonObject["type"]?.asString == "fragment" } != false &&
                    NativeOptions.get(profile.options?.forEngine("xray") ?: JsonObject(), "streamSettings.finalmask.tcp")
                        ?.asJsonArray?.none { it.asJsonObject["type"]?.asString == "fragment" } != false) {
                    "Одновременно заданы fragment и нативная фрагментация FinalMask; выберите один источник"
                }
            }
        }

        /** Apply only to executable copies. Existing link/native settings always take precedence. */
        fun applyDefault(profile: VpnConfig, defaults: VpnFragmentation?): VpnConfig {
            if (defaults == null || defaults.mode == "off" || profile.options?.fragmentation != null ||
                profile.protocol != "vless" || fromUri(profile.uri) != null || compatibilityReason(profile) != null) return profile
            val q = parameters(profile)
            if (q["fm"] != null || NativeOptions.get(profile.options?.forEngine("xray") ?: JsonObject(), "streamSettings.finalmask") != null)
                return profile
            defaults.validate()
            return profile.copy(options = (profile.options ?: VpnOptions()).copy(fragmentation = defaults))
        }

        fun applyDefault(config: ClientConfig, defaults: VpnFragmentation?): ClientConfig {
            val vpn = config.kernelConfig as? KernelConfig.Vpn ?: return config
            return config.copy(kernelConfig = KernelConfig.Vpn(applyDefault(vpn.config, defaults)))
        }

        fun xrayPatch(profile: VpnConfig, patch: JsonObject): JsonObject {
            val setting = effective(profile)
            if (profile.protocol != "vless" && setting == null) return patch
            validateFor(profile)
            val mask = combinedMask(profile, patch)
            val normalized = normalizeFragmentMasks(mask)
            if (setting == null) return if (normalized) NativeOptions.put(patch, "streamSettings.finalmask", mask) else patch
            val tcp = JsonArray().apply {
                mask.getAsJsonArray("tcp")?.forEach { if (it.asJsonObject["type"]?.asString != "fragment") add(it.deepCopy()) }
                if (setting.mode != "off") add(setting.xrayMask())
            }
            // Empty array intentionally overrides a fragment inherited from the URI mask.
            mask.add("tcp", tcp)
            return NativeOptions.put(patch, "streamSettings.finalmask", mask)
        }

        private fun combinedMask(profile: VpnConfig, patch: JsonObject): JsonObject {
            val raw = parameters(profile)["fm"]?.let { com.google.gson.JsonParser.parseString(it).asJsonObject } ?: JsonObject()
            return NativeOptions.merge(raw, NativeOptions.get(patch, "streamSettings.finalmask")?.asJsonObject ?: JsonObject())
        }

        private fun normalizeFragmentMasks(mask: JsonObject): Boolean {
            var changed = false
            mask.getAsJsonArray("tcp")?.forEach { entry ->
                val item = entry.asJsonObject
                if (item["type"]?.asString != "fragment") return@forEach
                val settings = requireNotNull(item.getAsJsonObject("settings")) { "FinalMask fragment: отсутствуют settings" }
                require(settings.keySet().all { it in setOf("packets", "length", "delay", "lengths", "delays", "maxSplit", "interval") }) {
                    "FinalMask fragment: неизвестные параметры закреплённого ядра"
                }
                settings["interval"]?.let { interval ->
                    require(!settings.has("delays") && (!settings.has("delay") || settings["delay"] == interval)) {
                        "FinalMask fragment: interval конфликтует с delay/delays"
                    }
                    // Legacy freedom fragments call this interval; FinalMask calls it delay.
                    // Keep the source URI intact but never silently drop the requested delay.
                    settings.remove("interval")
                    settings.add("delay", interval)
                    changed = true
                }
            }
            return changed
        }
    }
}
