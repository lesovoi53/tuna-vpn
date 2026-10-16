package com.wireturn.app.domain

import com.wireturn.app.data.Profile
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.AwgUri

/** Collapse one AWG account across link formats while retaining original tuning variants. */
object AwgProfileDedup {
    fun duplicateIssues(profiles: List<Profile>, preferredId: String? = null): List<com.wireturn.app.data.SubscriptionImportIssue> =
        profiles.filter { key(it) != null }.groupBy { key(it)!! }.values.flatMap { batch ->
            val chosen = batch.indexOfFirst { it.id == preferredId }.takeIf { it >= 0 } ?: 0
            batch.filterIndexed { index, _ -> index != chosen }.map {
                com.wireturn.app.data.SubscriptionImportIssue.profile(it, duplicate = true)
            }
        }

    fun key(profile: Profile): String? = (profile.kernelConfig as? KernelConfig.Vpn)?.config?.let { vpn ->
        if (vpn.protocol !in setOf("awg", "wireguard")) null else
            AwgUri.accountIdentity(vpn.uri) + "|" + vpn.options?.singBox?.takeIf { it.size() > 0 }?.toString().orEmpty()
    }
    fun <T> filter(items: List<T>, profile: (T) -> Profile, preferredId: String? = null,
        withProfile: (T, Profile) -> T = { original, _ -> original }): List<T> {
        val batches = items.filter { key(profile(it)) != null }.groupBy { key(profile(it))!! }
        val seen = mutableSetOf<String>()
        return items.mapNotNull { item ->
            val key = key(profile(item))
            if (key == null) item else if (!seen.add(key)) null else {
                val batch = batches.getValue(key)
                val selected = batch.firstOrNull { profile(it).id == preferredId } ?: batch.first()
                val chosen = profile(selected)
                val vpn = (chosen.kernelConfig as KernelConfig.Vpn).config
                val variants = batch.flatMap { (profile(it).kernelConfig as KernelConfig.Vpn).config.let { c -> listOf(c.uri) + c.importedVariants } }
                    .distinct().filter { it != vpn.uri }.take(16)
                withProfile(selected, chosen.copy(kernelConfig = KernelConfig.Vpn(vpn.copy(importedVariants=variants))))
            }
        }
    }
}
