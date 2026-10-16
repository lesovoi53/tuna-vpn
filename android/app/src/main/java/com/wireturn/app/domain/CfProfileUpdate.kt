package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile

/** Address edits keep the current profile's metadata, including local preferences. */
object CfProfileUpdate {
    fun selection(current: Profile, record: CfSavedAddresses, selectedIp: String?): Profile {
        require(record.accepts(current))
        require(selectedIp == null || record.results.any { it.ip == selectedIp && it.usable })
        val plan = CloudflareProfile.plan(CloudflareProfile.restore(record.source))
        return current.copy(kernelConfig = plan.profileFor(selectedIp).kernelConfig)
    }

    fun keepAddress(current: Profile, enabled: Boolean, saved: CfSavedAddresses?): Pair<Profile, CfSavedAddresses?> {
        require(current.subscriptionId != null && current.kernelConfig is KernelConfig.Vpn)
        // Materialize the rebased Original choice before switching off the preference:
        // fromPinned deliberately only recovers originals for an opted-in profile.
        val record = saved?.forCurrent(current) ?: CfSavedAddresses.fromPinned(current)
        return current.copy(keepSubscriptionAddress = enabled) to record
    }
}
