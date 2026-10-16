package com.wireturn.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.domain.SubscriptionGroupMerge

/** One obsolete group cannot hide unrelated groups; missing members require explicit repair. */
internal fun decodeStoredGroups(json: String, profiles: List<Profile>): List<ConnectionGroup> {
    val gson = Gson()
    val entries = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
    val groups = entries.mapNotNull { entry ->
        runCatching {
            val group = gson.fromJson(entry, ConnectionGroup::class.java) ?: return@mapNotNull null
            require((group.id as String?).isNullOrBlank().not())
            require(group.memberIds.all { (it as String?) != null })
            require((group.category as ConnectionCategory?) != null && (group.type as GroupTestType?) != null)
            val family = group.family ?: profiles.filter { it.id in group.memberIds }.mapNotNull { it.groupFamily }.distinct().singleOrNull()
            group.copy(family = family)
        }.getOrNull()
    }
    return SubscriptionGroupMerge.reconcileLocal(groups, profiles)
}
