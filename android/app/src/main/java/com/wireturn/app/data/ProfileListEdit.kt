package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** A user operation queued for the preferences transaction. */
internal sealed interface ProfileListEdit {
    data class Rename(val id: String, val name: String) : ProfileListEdit
    data class Remove(val ids: Set<String>) : ProfileListEdit
    data class Append(val profile: Profile) : ProfileListEdit
    data class AppendMany(val profiles: List<Profile>) : ProfileListEdit
    data class Replace(val profile: Profile) : ProfileListEdit
    data class Reorder(val ids: List<String>) : ProfileListEdit

    fun commit(preferences: MutablePreferences, gson: Gson): List<Profile> {
        val current = JsonParser.parseString(preferences[AppPreferences.PROFILES_JSON] ?: "[]").asJsonArray
        fun JsonElement.profileId(): String? = runCatching { asJsonObject.get("id")?.asString }.getOrNull()
        val result = JsonArray()
        when (this) {
            is Reorder -> {
                // Dragging changes order only. Refreshed content and newly added entries survive.
                val positions = ids.distinct().withIndex().associate { it.value to it.index }
                current.sortedBy { positions[it.profileId()] ?: Int.MAX_VALUE }.forEach(result::add)
            }
            else -> {
                current.forEach { entry ->
                    val id = entry.profileId()
                    when {
                        this is Remove && id in ids -> Unit
                        this is Rename && id == this.id -> result.add(entry.apply { asJsonObject.addProperty("name", name) })
                        this is Replace && id == profile.id -> result.add(gson.toJsonTree(profile))
                        else -> result.add(entry)
                    }
                }
                // A delayed replacement never resurrects a profile removed by an update.
                val additions = when (this) {
                    is Append -> listOf(profile)
                    is AppendMany -> profiles
                    else -> emptyList()
                }
                val ids = current.mapNotNull { it.profileId() }.toMutableSet()
                additions.forEach { if (ids.add(it.id)) result.add(gson.toJsonTree(it)) }
            }
        }
        // Parse before writing: any invalid input leaves the transaction untouched.
        val profiles = result.map { gson.fromJson(it, Profile::class.java) }
        preferences[AppPreferences.PROFILES_JSON] = result.toString()
        return profiles
    }
}
