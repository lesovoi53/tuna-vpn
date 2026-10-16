package com.wireturn.app.domain

import com.wireturn.app.data.ConnectionCategory
import com.wireturn.app.data.Profile
import com.wireturn.app.data.listCategory

/** List navigation only: resolving an import never selects or connects a profile. */
data class ProfileRevealPlan(
    val targetId: String,
    val category: ConnectionCategory,
    val subscriptions: Boolean,
    val section: ProfileSection?,
    val expandSubscriptionId: String?,
    val manageSubscriptions: Boolean,
    val lazyKey: String,
    val highlightedIds: List<String>
)

object ProfileReveal {
    /** Null means the requested item has not reached the observed store yet; keep waiting. */
    fun resolve(targetId: String, profiles: List<Profile>, subscriptionIds: Set<String>, favorites: Set<String>,
        currentCategory: ConnectionCategory, embedded: Boolean): ProfileRevealPlan? {
        val profile = profiles.find { it.id == targetId }
        if (profile != null) {
            val pinned = ProfileSections.isPinnedBalancer(profile)
            return ProfileRevealPlan(targetId, profile.listCategory, profile.subscriptionId != null,
                if (pinned) null else if (ProfileListOrder.isFavorite(profile, favorites)) ProfileSection.FAVORITES else ProfileSections.section(profile),
                profile.subscriptionId, false,
                if (pinned) "pinned-balancer:$targetId" else if (embedded) "section-profile:$targetId" else targetId,
                listOfNotNull(targetId, profile.subscriptionId))
        }
        if (targetId !in subscriptionIds) return null
        val members = profiles.filter { it.subscriptionId == targetId }
        val category = members.firstOrNull { it.listCategory == currentCategory }?.listCategory
            ?: members.firstOrNull()?.listCategory ?: currentCategory
        return ProfileRevealPlan(targetId, category, true, null, null, embedded,
            if (embedded) "section-sub:$targetId" else "sub_header_$targetId",
            listOf(targetId) + members.map { it.id })
    }

    /** Mirrors embedded LazyColumn rows, including empty expanded sections and management rows. */
    fun embeddedLazyIndex(targetId: String, sections: List<Pair<ProfileSection, List<Profile>>>,
        expanded: ProfileSection?, searching: Boolean, leadingItems: Int,
        showSubscriptions: Boolean, manageSubscriptions: Boolean, subscriptionIds: List<String>): Int {
        var index = leadingItems
        for ((section, members) in sections) {
            index++ // Section header.
            if (expanded == section || searching) {
                val found = members.indexOfFirst { it.id == targetId }
                if (found >= 0) return index + found
                index += members.size.coerceAtLeast(1)
            }
        }
        if (showSubscriptions && manageSubscriptions) {
            index++ // Subscription management button.
            val found = subscriptionIds.indexOf(targetId)
            if (found >= 0) return index + found
        }
        return -1
    }
}
