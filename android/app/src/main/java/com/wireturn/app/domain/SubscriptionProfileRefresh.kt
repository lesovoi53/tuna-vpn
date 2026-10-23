package com.wireturn.app.domain

import com.wireturn.app.data.Profile
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.category

/** Builds a refreshed subscription profile while keeping its local identity. */
internal object SubscriptionProfileRefresh {
    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()

    fun apply(incoming: Profile, existing: Profile?, localId: String, subscriptionId: String,
        sourceId: String?, name: String = incoming.name): Profile {
        require(existing == null || existing.category == incoming.category) {
            "Subscription changed profile category; previous settings preserved"
        }
        // Reuse the normalized server snapshot, not a user's local fingerprint edit.
        // A forbidden/empty server fp is randomized once; explicit valid server values
        // still replace it on refresh just like every other connection parameter.
        val previous = existing?.let { old -> old.copy(kernelConfig = old.subscriptionKernelBaseline?.let {
            gson.fromJson(it, KernelConfig::class.java)
        } ?: old.kernelConfig) }
        // Subscription payloads own the source pool; only an existing local profile
        // owns the personal selection. Never import another profile's local choice.
        val source = when (val kernel = incoming.kernelConfig) {
            is KernelConfig.MasterDns -> incoming.copy(kernelConfig = KernelConfig.MasterDns(
                kernel.config.copy(localResolvers = null, localDomainResolvers = null, useLocalResolvers = null, localPerformanceMode = null,
                    localPool = null)))
            else -> incoming
        }
        val normalized = VlessFingerprintImport.profile(source.sanitize(), previous)
        val remoteKernel = normalized.kernelConfig
        // Keep local edits separately from the unmodified remote baseline. This also lets
        // a later refresh adopt remote fields that the user has not customized.
        val remote = normalized.copy(kernelConfig = remoteKernel)
        val keepAddress = SubscriptionAddressPolicy.canKeep(remote, existing)
        val mergedKernel = existing?.let { SubscriptionProfileMerge.kernel(it, remote) } ?: remoteKernel
        val auxiliary = SubscriptionProfileMerge.auxiliary(existing, remote)
        val localName = existing?.name?.takeIf { existing.subscriptionOriginalName != null && it != existing.subscriptionOriginalName }
        return remote.copy(
            kernelConfig = if (keepAddress) SubscriptionAddressPolicy.preserve(remote.copy(kernelConfig = mergedKernel), existing!!) else mergedKernel,
            keepSubscriptionAddress = keepAddress,
            id = localId,
            name = localName ?: name,
            xrayEnabled = auxiliary.xrayEnabled,
            xrayProtocol = auxiliary.xrayProtocol,
            wgConfig = auxiliary.wgConfig,
            vlessConfig = auxiliary.vlessConfig,
            subscriptionId = subscriptionId,
            subscriptionSourceId = sourceId,
            subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(remote.kernelConfig),
            subscriptionAuxBaseline = SubscriptionProfileMerge.auxiliarySnapshot(remote),
            subscriptionOriginalName = name,
            retainedFromSubscription = null,
            retainedSubscriptionId = null
        )
    }
}
