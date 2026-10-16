package com.wireturn.app.domain

import com.wireturn.app.data.Profile
import com.wireturn.app.data.KernelConfig
import java.util.Locale

enum class ProfileSortOrder { ORIGINAL, NAME_ASC, NAME_DESC, PROTOCOL }

/** Local presentation preferences are independent of authoritative subscription settings. */
object ProfileListOrder {
    fun key(profile: Profile): String {
        val subscription = profile.subscriptionId
        val source = profile.subscriptionSourceId
        return if (!subscription.isNullOrBlank() && !source.isNullOrBlank())
            "subscription:${subscription.length}:$subscription:${source.length}:$source"
        else "profile:${profile.id}"
    }
    fun isFavorite(profile: Profile, favorites: Set<String>) = key(profile) in favorites
    /** Matching has already established local continuity; URI source hashes may have changed. */
    fun afterRefresh(previous: List<Profile>, current: List<Profile>, favorites: Set<String>): Set<String> {
        val nextById = current.groupBy { it.id }
        val currentKeys = current.map(::key).toSet()
        val result = favorites.toMutableSet()
        previous.groupBy { it.id }.values.mapNotNull { it.singleOrNull() }.forEach { old ->
            val next = nextById[old.id]?.singleOrNull() ?: return@forEach
            val previousOwner = old.subscriptionId ?: old.retainedSubscriptionId
            val nextOwner = next.subscriptionId ?: next.retainedSubscriptionId
            if (previousOwner.isNullOrBlank() || previousOwner != nextOwner) return@forEach
            val oldKey = key(old)
            val nextKey = key(next)
            if (oldKey in favorites && oldKey != nextKey) {
                if (oldKey !in currentKeys) result.remove(oldKey)
                result.add(nextKey)
            }
        }
        return result
    }
    fun toggled(current: Profile?, requested: Profile, favorites: Set<String>): Set<String> {
        if (current == null || current.id != requested.id || current.subscriptionId != requested.subscriptionId) return favorites
        val key = key(current)
        return if (key in favorites) favorites - key else favorites + key
    }
    fun sorted(profiles: List<Profile>, order: ProfileSortOrder, favorites: Set<String>): List<Profile> {
        val priority = compareByDescending<Profile> { isFavorite(it, favorites) }
        val name = compareBy<Profile> { it.name.lowercase(Locale.ROOT) }
        val comparator = when (order) {
            ProfileSortOrder.ORIGINAL -> priority
            ProfileSortOrder.NAME_ASC -> priority.then(name)
            ProfileSortOrder.NAME_DESC -> priority.then(name.reversed())
            ProfileSortOrder.PROTOCOL -> priority.then(compareBy { protocol(it) }).then(name)
        }
        // Stable sort preserves subscription order for ties and never modifies the backing list.
        return profiles.sortedWith(comparator)
    }
    private fun protocol(profile: Profile) = (profile.kernelConfig as? KernelConfig.Vpn)?.config?.protocol
        ?: profile.kernelVariant.name.lowercase(Locale.ROOT)
}
