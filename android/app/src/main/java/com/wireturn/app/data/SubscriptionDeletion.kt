package com.wireturn.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

internal data class SubscriptionDeletionResult(
    val profiles: List<Profile>,
    val subscriptions: List<Subscription>,
    val removedSelectedId: String?,
)

internal suspend fun deleteSubscriptionState(
    store: DataStore<Preferences>,
    id: String,
    readSnapshot: (Preferences) -> SubscriptionImportSnapshot,
    gson: Gson,
): SubscriptionDeletionResult {
    var result: SubscriptionDeletionResult? = null
    store.edit { preferences ->
        // Read inside the same transaction: refresh, rename and selection edits may have
        // completed while this request was waiting for the DataStore writer.
        val snapshot = readSnapshot(preferences)
        val profiles = snapshot.profiles.filter { it.subscriptionId != id }
        val subscriptions = snapshot.subscriptions.filter { it.id != id }
        fun withoutOwned(json: String, field: String): String = JsonArray().apply {
            JsonParser.parseString(json).asJsonArray.forEach { entry ->
                val owner = entry.takeIf { it.isJsonObject }?.asJsonObject?.get(field)
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (owner != id) add(entry)
            }
        }.toString()
        // A delete must preserve unrecognised fields on surviving records too.
        val profilesJson = withoutOwned(snapshot.profilesJson, "subscriptionId")
        val subscriptionsJson = withoutOwned(snapshot.subscriptionsJson, "id")
        SubscriptionImportCommit.write(preferences, snapshot, profiles, subscriptions, null, gson)
        preferences[AppPreferences.PROFILES_JSON] = profilesJson
        preferences[AppPreferences.SUBSCRIPTIONS_JSON] = subscriptionsJson
        result = SubscriptionDeletionResult(profiles, subscriptions, snapshot.currentProfileId.takeIf { selected ->
            snapshot.profiles.any { it.id == selected && it.subscriptionId == id }
        })
    }
    return requireNotNull(result)
}
