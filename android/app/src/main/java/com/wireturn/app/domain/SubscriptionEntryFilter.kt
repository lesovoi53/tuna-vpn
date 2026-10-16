package com.wireturn.app.domain

import com.google.gson.JsonObject
import com.wireturn.app.kernel.KernelRegistry

/** Invalid individual endpoints do not invalidate a transport-valid subscription response. */
object SubscriptionEntryFilter {
    fun supported(uri: String): Boolean = runCatching { KernelRegistry.decodeUri(uri) != null }.getOrDefault(false)

    fun filterDocument(source: JsonObject, onRejected: (com.google.gson.JsonElement, Int) -> Unit = { _, _ -> }): Pair<JsonObject, Int> {
        val result = source.deepCopy()
        val entries = result.getAsJsonArray("profiles") ?: return result to 0
        val rejected = entries.filter { !supported(it.asJsonObject["uri"].asString) }
            .map { it.asJsonObject["id"].asString }.toSet()
        entries.forEachIndexed { index, entry -> if (entry.asJsonObject["id"].asString in rejected) onRejected(entry, index) }
        val kept = com.google.gson.JsonArray()
        entries.filter { it.asJsonObject["id"].asString !in rejected }.forEach(kept::add)
        result.add("profiles", kept)
        // Only references to rejected endpoints are removed. Other malformed group data
        // remains a validation error, rather than being silently interpreted differently.
        if (rejected.isNotEmpty()) result.getAsJsonArray("groups")?.let { groups ->
            val usable = com.google.gson.JsonArray()
            groups.forEach { item ->
                val group = item.asJsonObject
                val members = group.getAsJsonArray("memberIds")
                val remaining = members.filter { it.asString !in rejected }
                group.add("memberIds", com.google.gson.JsonArray().apply { remaining.forEach(::add) })
                val anchorRemoved = (group["routingProfileId"]?.asString ?: members.firstOrNull()?.asString) in rejected
                if (remaining.size < 2 || anchorRemoved) {
                    group.addProperty("repairRequired", true)
                    group.addProperty("automatic", false)
                    group.addProperty("testOnConnect", false)
                    if (anchorRemoved) group.addProperty("routingProfileId", "")
                }
                usable.add(group)
            }
            result.add("groups", usable)
        }
        return result to rejected.size
    }
}
