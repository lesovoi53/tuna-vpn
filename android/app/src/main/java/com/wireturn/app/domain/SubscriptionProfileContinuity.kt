package com.wireturn.app.domain

import com.wireturn.app.data.Profile

/** Referenced or customized entries survive remote removal as explicit local copies. */
internal object SubscriptionProfileContinuity {
    fun belongs(profile: Profile, subscriptionId: String) =
        profile.subscriptionId == subscriptionId || profile.retainedSubscriptionId == subscriptionId

    fun isCustomized(profile: Profile): Boolean {
        if (profile.keepSubscriptionAddress || profile.retainedSubscriptionId != null) return true
        if (profile.subscriptionOriginalName != null && profile.name != profile.subscriptionOriginalName) return true
        if (profile.subscriptionKernelBaseline != null && SubscriptionProfileMerge.snapshot(profile.kernelConfig) !=
            SubscriptionProfileMerge.normalizedSnapshot(profile.subscriptionKernelBaseline)) return true
        if (profile.subscriptionAuxBaseline != null && SubscriptionProfileMerge.auxiliarySnapshot(profile) != profile.subscriptionAuxBaseline) return true
        if (profile.subscriptionKernelBaseline == null) {
            val vpn = (profile.kernelConfig as? com.wireturn.app.data.KernelConfig.Vpn)?.config
            if (vpn?.options != null || (vpn != null && vpn.selectedEngine != "auto")) return true
        }
        return false
    }

    fun retainedMissing(previous: List<Profile>, incoming: List<Profile>, subscriptionId: String,
        subscriptionName: String, protectedIds: Set<String>): List<Profile> {
        val incomingIds = incoming.map { it.id }.toSet()
        return previous.filter { belongs(it, subscriptionId) && it.id !in incomingIds &&
            (it.id in protectedIds || isCustomized(it)) }.map {
                it.copy(subscriptionId = null, retainedSubscriptionId = subscriptionId,
                    retainedFromSubscription = subscriptionName)
            }
    }

    fun afterRefresh(previous: List<Profile>, incoming: List<Profile>, subscriptionId: String, subscriptionName: String,
        protectedIds: Set<String> = emptySet()): List<Profile> =
        previous.filterNot { belongs(it, subscriptionId) } + incoming +
            retainedMissing(previous, incoming, subscriptionId, subscriptionName, protectedIds)

    /** Background metadata updates must not restart the user's running session. */
    fun mayActivateAfterRefresh(subscriptionId: String?, tunnelBusy: Boolean) = subscriptionId == null || !tunnelBusy
}
