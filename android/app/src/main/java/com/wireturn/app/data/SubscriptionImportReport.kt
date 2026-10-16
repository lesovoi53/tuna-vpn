package com.wireturn.app.data

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.wireturn.app.data.kernel.VpnConfig

/** Contains presentation metadata only: never retain input links, config JSON or parser errors. */
data class SubscriptionImportReport(
    @SerializedName("rejected") val rejected: List<SubscriptionImportIssue> = emptyList(),
    @SerializedName("duplicates") val duplicates: List<SubscriptionImportIssue> = emptyList()
)

data class SubscriptionImportIssue(
    @SerializedName("name") val name: String,
    @SerializedName("protocol") val protocol: String,
    @SerializedName("reason") val reason: String
) {
    companion object {
        private val knownProtocols = setOf("wg", "awg", "wireguard", "vpn", "vless", "vmess", "trojan",
            "ss", "shadowsocks", "ssr", "snell", "hysteria", "hysteria2", "hy2", "tuic", "anytls", "naive",
            "naive+https", "naive+quic", "mierus", "mieru", "tt", "trusttunnel",
            "webdav", "webdavs", "wdtt", "qwdtt", "stormdns", "masterdnsvpn", "tuna", "wireturn", "wt", "olcrtc", "olconnect", "freeturn", "turnable")
        private val unsupported = setOf("vmess", "ssr", "hysteria", "olcrtc", "olconnect", "freeturn", "turnable")
        private val secret = Regex("(?i)([a-z0-9+/_-]{40,}={0,2}|private.?key|public.?key|password|psk\\s*=|token\\s*=|://)")

        private fun safeName(value: String?, fallback: String): String {
            val decoded = runCatching { VpnConfig.decode(value.orEmpty()) }.getOrDefault("")
                .replace(Regex("[\\p{Cntrl}]"), " ").trim()
            return if (decoded.isBlank() || secret.containsMatchIn(decoded)) fallback else decoded.take(100)
        }

        fun uri(uri: String, suppliedName: String? = null, index: Int? = null): SubscriptionImportIssue {
            val scheme = uri.substringBefore(':', "").lowercase()
            val protocol = scheme.takeIf { it in knownProtocols } ?: "неизвестный"
            val fallback = index?.let { "Профиль ${it + 1}" } ?: "Профиль без имени"
            return SubscriptionImportIssue(safeName(suppliedName ?: uri.substringAfter('#', ""), fallback), protocol,
                if (protocol == "неизвестный" || scheme in unsupported) "Протокол не поддерживается"
                else "Некорректные или неподдерживаемые параметры профиля")
        }

        fun json(value: JsonElement, index: Int): SubscriptionImportIssue {
            val fields = value.takeIf { it.isJsonObject }?.asJsonObject
            fun string(key: String) = fields?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val source = string("uri")
            if (source != null) return uri(source, string("name"), index)
            val type = fields?.get("kernelConfig")?.takeIf { it.isJsonObject }?.asJsonObject?.get("type")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
            return uri("$type:", string("name"), index).copy(reason = "Некорректная или неподдерживаемая конфигурация профиля")
        }

        fun profile(profile: Profile, duplicate: Boolean = false): SubscriptionImportIssue {
            val vpn = profile.kernelConfig as? KernelConfig.Vpn
            return uri(vpn?.config?.uri.orEmpty(), profile.name).copy(reason = if (duplicate)
                "Объединён с другим представлением того же подключения WG/AWG; варианты параметров сохранены"
                else "Профиль не прошёл проверку конфигурации")
        }
    }
}
