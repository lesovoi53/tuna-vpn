package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.data.kernel.MasterDnsConfig

data class MasterDnsPoolMember(val id: String, val name: String, val config: MasterDnsConfig)

/** Launch-only snapshot, never a stored replacement for the selected profile IDs. */
data class MasterDnsPoolPlan(val strategy: String, val members: List<MasterDnsPoolMember>) {
    companion object {
        fun resolve(
            anchor: Profile,
            profiles: List<Profile>,
            effectiveRouting: Map<String, SharedNetworkSettings>,
        ): MasterDnsPoolPlan? {
            val selection = (anchor.kernelConfig as? KernelConfig.MasterDns)?.config?.localPool ?: return null
            selection.validate()
            require(anchor.id in selection.memberIds) { "Основной сервер должен входить в пул Master DNS" }
            val indexed = profiles.groupBy { it.id }
            val anchorRouting = requireNotNull(effectiveRouting[anchor.id]) {
                "Не определены общие DNS и маршрутизация основного сервера пула Master DNS"
            }
            val members = selection.memberIds.map { id ->
                val matches = indexed[id].orEmpty()
                require(matches.size == 1) {
                    if (matches.isEmpty()) "Сервер пула Master DNS удалён или недоступен: $id"
                    else "Повторяющийся идентификатор сервера пула Master DNS: $id"
                }
                val profile = if (id == anchor.id) anchor else matches.single()
                val config = (profile.kernelConfig as? KernelConfig.MasterDns)?.config
                requireNotNull(config) { "Сервер «${profile.name}» больше не использует Master DNS" }
                require(profile.subscriptionId == anchor.subscriptionId) {
                    "Серверы пула Master DNS должны принадлежать одной подписке либо все быть собственными"
                }
                require(id == anchor.id || config.localPool == null) {
                    "Вложенные пулы Master DNS не поддерживаются: ${profile.name}"
                }
                val routing = requireNotNull(effectiveRouting[id]) {
                    "Не определены общие DNS и маршрутизация сервера «${profile.name}»"
                }
                require(routing == anchorRouting) {
                    "Общие DNS и маршрутизация серверов пула Master DNS должны совпадать"
                }
                require(sameProxyChain(anchor, profile)) {
                    "Настройки дополнительной цепочки прокси серверов пула Master DNS должны совпадать"
                }
                val child = config.copy(localPool = null)
                try {
                    child.validate()
                    require(child.settings["SAVE_MTU_SERVERS_TO_FILE"] != "true") {
                        "Отключите сохранение результатов MTU в файл для работы нескольких серверов"
                    }
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Некорректные настройки сервера пула Master DNS «${profile.name}»: ${failure.message}", failure)
                }
                MasterDnsPoolMember(id, profile.name, child)
            }
            return MasterDnsPoolPlan(selection.strategy, members)
        }

        private fun sameProxyChain(anchor: Profile, member: Profile): Boolean {
            return effectiveProxyChain(anchor) == effectiveProxyChain(member)
        }

        private fun effectiveProxyChain(profile: Profile): VlessConfig {
            // XrayService does not use WG settings for SOCKS-native kernels. Disabled
            // chains and saved but unused links must not prevent a standalone pool.
            if (!profile.xrayEnabled || profile.xrayProtocol != XrayConfiguration.VLESS) return VlessConfig()
            val config = profile.vlessConfig.sanitize().fillDefaults()
            // One shared router wraps the combined listener using the anchor's chain.
            // Match every active VLESS/dual-route option, including mux; omit only
            // fields that the SOCKS-native launcher does not pass to that router.
            return config.copy(
                vlessLink = config.vlessLink.takeIf { config.isSocks5Chain || config.isDualRoute }.orEmpty(),
                directAddress = config.directAddress.takeIf { config.isDualRoute }.orEmpty(),
                hcInterval = if (config.isDualRoute) config.hcInterval else "30",
                hcDestination = config.hcDestination.takeIf { config.isDualRoute }.orEmpty(),
            )
        }
    }
}
