package com.wireturn.app.domain

import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.MasterDnsGroup
import com.wireturn.app.data.kernel.VpnFragmentation
import com.wireturn.app.ui.ValidatorUtils

/** One persisted revision, including local unsaved anchor edits and current member credentials. */
data class RuntimeClientSnapshot(
    val config: ClientConfig,
    val profileId: String,
    val profileName: String,
    val fragmentation: VpnFragmentation? = null,
    val poolRouting: SharedNetworkSettings? = null,
    val error: String? = null,
    val masterDnsGroupId: String? = null,
) {
    fun runtimeConfig(): ClientConfig {
        require(error == null) { error.orEmpty() }
        return VpnFragmentation.applyDefault(config.fillDefaults(), fragmentation)
    }

    companion object {
        fun resolve(
            config: ClientConfig,
            profileId: String,
            profiles: List<Profile>,
            subscriptions: List<Subscription>,
            shared: SharedNetworkSettings,
            fragmentation: VpnFragmentation? = null,
            masterDnsGroups: List<MasterDnsGroup> = emptyList(),
            activeMasterDnsGroupId: String? = null,
        ): RuntimeClientSnapshot {
            if (activeMasterDnsGroupId != null) {
                val group = masterDnsGroups.singleOrNull { it.id == activeMasterDnsGroupId }
                val base = RuntimeClientSnapshot(config.copy(masterDnsPoolPlan = null), profileId,
                    group?.name ?: "Группа DNS", fragmentation, masterDnsGroupId = activeMasterDnsGroupId)
                return try {
                    requireNotNull(group) { "Группа DNS удалена или неоднозначна" }
                    val members = group.members(profiles)
                    require(profileId == members.first().id) { "Основной сервер группы DNS изменился" }
                    val anchor = members.first()
                    // Every group member, including its anchor, comes from the latest real profile.
                    // The active snapshot can be older than a subscription refresh.
                    val kernel = anchor.kernelConfig as KernelConfig.MasterDns
                    val launch = config.copy(kernelConfig = KernelConfig.MasterDns(
                        kernel.config.copy(localPool = group.selection())), masterDnsPoolPlan = null)
                    resolve(launch, profileId, profiles, subscriptions, shared, fragmentation)
                        .copy(profileName = group.name, masterDnsGroupId = group.id)
                } catch (failure: Exception) {
                    base.copy(error = failure.message ?: "Некорректная группа DNS")
                }
            }
            val base = RuntimeClientSnapshot(config.copy(masterDnsPoolPlan = null), profileId,
                profiles.firstOrNull { it.id == profileId }?.name.orEmpty(), fragmentation)
            val selection = (config.kernelConfig as? KernelConfig.MasterDns)?.config?.localPool
                ?: return base
            // Error is data, so a malformed refresh cannot kill the preferences observer.
            return try {
                selection.validate()
                val storedAnchor = profiles.filter { it.id == profileId }.singleOrNull()
                requireNotNull(storedAnchor) { "Основной сервер пула Master DNS удалён или неоднозначен" }
                val anchor = storedAnchor.copy(kernelConfig = config.kernelConfig)
                require(config.isSocksAuthEnabled && ValidatorUtils.isLoopbackHostPort(config.socksAddr)) {
                    "Для пула Master DNS нужен локальный SOCKS с включённой аутентификацией"
                }
                val common = ProfileRouting.common(shared, subscriptions)
                val members = profiles.filter { it.id in selection.memberIds }
                    .map { if (it.id == profileId) anchor else it }
                for (member in members) {
                    member.subscriptionId?.let { id ->
                        require(subscriptions.count { it.id == id } == 1) {
                            "Подписка сервера «${member.name}» удалена или неоднозначна"
                        }
                    }
                }
                val routing = members.associate { it.id to ProfileRouting.resolve(common, it, subscriptions) }
                val plan = MasterDnsPoolPlan.resolve(anchor, profiles, routing)
                base.copy(config = base.config.copy(masterDnsPoolPlan = plan), poolRouting = routing[profileId])
            } catch (failure: Exception) {
                base.copy(error = failure.message ?: "Некорректный пул Master DNS")
            }
        }
    }
}
