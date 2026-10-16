package com.wireturn.app.domain

import com.google.gson.JsonParser
import com.wireturn.app.data.ConnectionGroup
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription

/** Merge only groups owned by changed subscriptions; preserve local edits against the old snapshot. */
object SubscriptionGroupMerge {
    /** Lost historical IDs cannot safely be guessed. Preserve the group, but require explicit repair. */
    fun reconcileLocal(groups: List<ConnectionGroup>, profiles: List<Profile>): List<ConnectionGroup> {
        val available = profiles.map { it.id }.toSet()
        return groups.map { group ->
            val members = group.memberIds.filter { it in available }
            if (members == group.memberIds && group.routingProfileId in members) group
            else group.copy(memberIds = members,
                routingProfileId = group.routingProfileId.takeIf { it in members }.orEmpty(),
                automatic = false, testOnConnect = false, repairRequired = true)
        }
    }
    fun merge(existing: List<ConnectionGroup>, oldSubscriptions: List<Subscription>,
              subscriptions: List<Subscription>, oldProfiles: List<Profile>, profiles: List<Profile>): List<ConnectionGroup> {
        val gson = com.google.gson.GsonBuilder().serializeNulls().create()
        var result = existing
        fun groups(sub: Subscription?, members: List<Profile>): List<ConnectionGroup> =
            if (sub?.tunaDocument.isNullOrBlank()) emptyList()
            else SubscriptionGroups.parse(SubscriptionEntryFilter.filterDocument(
                JsonParser.parseString(sub!!.tunaDocument).asJsonObject).first, sub.id, members)
        // Ownership survives local deletion of an endpoint; it does not require a valid runnable group.
        fun ownedIds(sub: Subscription?): Set<String> =
            if (sub?.tunaDocument.isNullOrBlank()) emptySet()
            else JsonParser.parseString(sub!!.tunaDocument).asJsonObject.getAsJsonArray("groups")?.map {
                val id = it.asJsonObject["id"]
                require(id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString && id.asString.isNotBlank())
                SubscriptionGroups.localId(sub.id, id.asString)
            }?.toSet().orEmpty()
        val retainedIds = subscriptions.map { it.id }.toSet()
        val removedGroupIds = oldSubscriptions.filter { it.id !in retainedIds }
            .flatMap(::ownedIds).toSet()
        result = result.filterNot { it.id in removedGroupIds }
        val availableIds = profiles.map { it.id }.toSet()
        for (sub in subscriptions) {
            val old = oldSubscriptions.find { it.id == sub.id }
            val oldMembers = oldProfiles.filter { it.subscriptionId == sub.id }
            val newMembers = profiles.filter { it.subscriptionId == sub.id }
            if (old?.tunaDocument == sub.tunaDocument && oldMembers == newMembers) continue
            val oldOwned = ownedIds(old)
            val incoming = groups(sub, profiles)
            if (incoming.isEmpty()) {
                // Removing a group from a remote snapshot is not consent to erase a
                // local group. Its retained profiles keep membership usable.
                continue
            }
            val oldSources = oldMembers.mapNotNull { it.subscriptionSourceId }.toSet()
            val historicalIds = oldProfiles.map { it.id }.toSet()
            // URI source hashes may rotate while SubscriptionIdentity deliberately retains
            // the local ID. The historical snapshot must keep its old version exactly once.
            val restoredMembers = newMembers.filter { it.subscriptionSourceId != null &&
                it.subscriptionSourceId !in oldSources && it.id !in historicalIds }
            val baselineMembers = (oldProfiles + restoredMembers).toMutableList()
            val baselineSources = baselineMembers.filter { it.subscriptionId == sub.id }
                .mapNotNull { it.subscriptionSourceId }.toMutableSet()
            // Historical endpoints exist only for comparing the previous snapshot, never for persistence or activation.
            if (!old?.tunaDocument.isNullOrBlank()) {
                val historicalDoc = JsonParser.parseString(old!!.tunaDocument).asJsonObject
                val referencedIds = historicalDoc.getAsJsonArray("groups")?.flatMap { item ->
                    val group = item.asJsonObject
                    group.getAsJsonArray("memberIds").map { it.asString } +
                        listOfNotNull(group["routingProfileId"]?.asString)
                }?.toSet().orEmpty()
                historicalDoc.getAsJsonArray("profiles")?.forEach { item ->
                    val entry = item.asJsonObject
                    val externalId = entry["id"].asString
                    if (externalId in referencedIds && externalId !in baselineSources) {
                        val config = com.wireturn.app.kernel.KernelRegistry.decodeUri(entry["uri"].asString)?.second
                            ?: return@forEach
                        val sourceId = externalId
                        if (baselineSources.add(sourceId)) {
                            baselineMembers += Profile(
                                id = java.util.UUID.nameUUIDFromBytes("tuna-history-profile:${sub.id}:$sourceId".toByteArray()).toString(),
                                name = entry["name"].asString, kernelConfig = config,
                                subscriptionId = sub.id, subscriptionSourceId = sourceId,
                            )
                        }
                    }
                }
            }
            val baseline = groups(old, baselineMembers).associateBy { it.id }
            val current = result.associateBy { it.id }
            val merged = incoming.map { remote ->
                val local = current[remote.id]
                val previous = baseline[remote.id]
                require(local == null || previous != null) { "Group ID belongs to an existing local group" }
                if (local == null || previous == null) remote else {
                    val next = gson.toJsonTree(remote).asJsonObject
                    val before = gson.toJsonTree(previous).asJsonObject
                    val edited = gson.toJsonTree(local).asJsonObject
                    val keepMembers = local.memberIds != previous.memberIds && local.memberIds.all { it in availableIds }
                    val nextMembers = if (keepMembers) local.memberIds else remote.memberIds
                    for ((key, value) in edited.entrySet()) {
                        // Restored endpoints have new local IDs. Stale membership/anchors must follow the new snapshot.
                        if (key == "repairRequired") continue
                        if (key == "memberIds" && !keepMembers) continue
                        if (key == "routingProfileId" && local.routingProfileId !in nextMembers) continue
                        if (key != "id" && value != before[key]) next.add(key, value.deepCopy())
                    }
                    gson.fromJson(next, ConnectionGroup::class.java)
                }.let { merged ->
                    // A remote repair requirement takes precedence over local automation edits.
                    if (merged.repairRequired) merged.copy(automatic = false, testOnConnect = false) else merged
                }.also { it.validate(profiles, allowRepair = true) }
            }
            val owned = incoming.map { it.id }.toSet()
            result = result.filter { it.id !in owned } + merged
        }
        require(result.map { it.id }.distinct().size == result.size) { "Duplicate group IDs" }
        return result
    }
}
