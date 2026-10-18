package com.wireturn.app.domain

import com.wireturn.app.R
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.SplitDnsOverride
import com.wireturn.app.data.XraySettings

/** Configured DNS from one settings snapshot; this does not observe resolver traffic. */
data class EffectiveDnsDisplay(
    val split: SplitDnsSettings?,
    val rows: List<ConnectionFact>,
    val notice: String? = null,
) {
    companion object {
        fun resolve(settings: XraySettings, profileBootstrap: String = "", rawXray: Boolean = false): EffectiveDnsDisplay {
            if (rawXray) return notice("DNS и маршруты заданы полным JSON Xray; общие настройки их не заменяют.")
            val split = try {
                val shared = SharedNetworkSettings.fromLegacy(settings)
                val resolved = NetworkPolicyResolver.resolve(shared)
                when (shared.dns.overrideMode) {
                    SplitDnsOverride.CUSTOM -> SplitDnsSettings.parse(shared.dns.splitDnsJson)
                    SplitDnsOverride.DISABLED -> null
                    SplitDnsOverride.INHERIT -> resolved.subscription?.let(SplitDnsSettings::fromPolicy)
                }
            } catch (_: Exception) {
                // A broken source must not be presented as a successfully applied common DNS.
                return notice("Не удалось прочитать настройки DNS текущего снимка.")
            }
            return EffectiveDnsDisplay(split, listOf(
                ConnectionFact(R.string.cd_remote_dns,
                    split?.let { pool(it.effectiveRemoteServers, it.remoteSelectionMode) + "\nАварийный резерв: " + recovery(it.remoteEmergency) } ?: settings.dns, sensitive = true),
                ConnectionFact(R.string.cd_direct_dns,
                    split?.let { pool(it.effectiveDomesticServers, it.domesticSelectionMode) + "\nАварийный резерв: " + recovery(it.domesticEmergency) }.orEmpty(),
                    valueResource = if (split == null) R.string.cd_not_separate else null, sensitive = true),
                ConnectionFact(R.string.cd_bootstrap,
                    split?.bootstrap?.let(::recovery) ?: settings.bootstrapDns.ifBlank { profileBootstrap }.let {
                        if (it == "system") recovery(DnsRecoverySettings("system")) else it.ifBlank { "По умолчанию ядра" }
                    },
                    sensitive = true),
            ))
        }

        private fun notice(text: String) = EffectiveDnsDisplay(null,
            listOf(ConnectionFact(R.string.cd_remote_dns, text)), text)

        fun recovery(settings: DnsRecoverySettings?): String = when (settings?.mode) {
            "system" -> "Системный DNS Android · напрямую (DIRECT), вне VPN"
            "resolver" -> settings.resolver?.let { server ->
                resolver(server) + if (server.route == com.wireturn.app.data.RouteAction.VPN) " · через VPN" else " · напрямую (DIRECT)"
            } ?: "Не задан сервер"
            else -> "Отключён"
        }

        fun observation(observed: DnsRuntimeTelemetry.Observation?, poolSize: Int): String {
            if (observed != null && observed.index == 0 && observed.reason.startsWith("emergency-")) {
                val source = if (observed.reason.startsWith("emergency-system"))
                    "системный DNS Android · напрямую (DIRECT), вне VPN"
                else "заданный аварийный DNS · " + if (observed.group == "remote") "через VPN" else "напрямую (DIRECT)"
                val negative = if (observed.reason.endsWith("-negative")) " · отрицательный DNS-ответ" else ""
                return "Последний ответ: аварийный резерв · $source$negative"
            }
            if (observed == null || observed.index !in 1..poolSize)
                return "Ядро ещё не сообщило фактически использованный DNS. Порядок списка не подтверждает выбор."
            val reason = when (observed.reason) {
                "fallback" -> "резерв после ошибки или таймаута"
                "probe" -> "проверка альтернативного DNS"
                "negative" -> "отрицательный DNS-ответ; не повод менять резолвер"
                else -> "выбран согласно режиму группы"
            }
            return "Последний ответ: DNS ${observed.index} · $reason"
        }

        private fun resolver(server: SubscriptionPolicyParser.Resolver): String =
            "${server.transport} · ${server.endpoint}" +
                server.bootstrapIp?.takeIf(String::isNotBlank)?.let { " · Bootstrap IP: $it" }.orEmpty()

        private fun pool(servers: List<SubscriptionPolicyParser.Resolver>, mode: String): String =
            (if (mode == "priority") "По порядку списка" else "Авто") + "\n" +
                servers.mapIndexed { index, server ->
                    "${index + 1}. ${resolver(server)}"
                }.joinToString("\n")
    }
}
