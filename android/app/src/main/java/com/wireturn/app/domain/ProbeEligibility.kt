package com.wireturn.app.domain

import com.wireturn.app.data.ConnectionCategory
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.listCategory

/** Individual URL/speed probes: a VLESS display category does not change its VPN transport. */
object ProbeEligibility {
    fun requiresActive(profile: Profile): Boolean = !allows(profile)

    fun sameSwitchType(source: Profile, candidate: Profile): Boolean {
        if (candidate.listCategory != source.listCategory || candidate.kernelVariant != source.kernelVariant) return false
        val vpn = source.kernelConfig as? KernelConfig.Vpn
        return vpn == null || vpn.config.protocol == (candidate.kernelConfig as? KernelConfig.Vpn)?.config?.protocol
    }

    fun sameSwitchGroup(source: Profile, group: com.wireturn.app.data.ConnectionGroup,
        profiles: List<Profile>): Boolean = source.listCategory == ConnectionCategory.VPN &&
        ManualGroupPolicy.allows(group) && group.memberIds.isNotEmpty() &&
        group.memberIds.all { id -> profiles.firstOrNull { it.id == id }?.let { sameSwitchType(source, it) } == true }

    fun allows(profile: Profile): Boolean = profile.listCategory == ConnectionCategory.VPN ||
        (profile.kernelConfig as? KernelConfig.Vpn)?.config?.protocol == "vless"
}
