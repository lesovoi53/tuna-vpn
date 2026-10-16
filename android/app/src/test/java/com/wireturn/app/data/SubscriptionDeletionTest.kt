package com.wireturn.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDeletionTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val target = Subscription("remove", "Remove", "https://example.invalid/a")
    private val other = Subscription("keep", "Keep", "https://example.invalid/b")
    private val a = Profile("a", "Remove", subscriptionId = target.id)
    private val b = Profile("b", "Keep", subscriptionId = other.id)

    private fun state(profiles: List<Profile> = listOf(a, b), subs: List<Subscription> = listOf(target, other), selected: String = a.id) = mutablePreferencesOf(
        AppPreferences.PROFILES_JSON to gson.toJson(profiles),
        AppPreferences.SUBSCRIPTIONS_JSON to gson.toJson(subs),
        AppPreferences.CURRENT_PROFILE_ID to selected,
    )

    private fun snapshot(p: Preferences) = SubscriptionImportSnapshot(
        decodeStoredProfiles(p[AppPreferences.PROFILES_JSON]!!, gson, "Profile"),
        JsonParser.parseString(p[AppPreferences.SUBSCRIPTIONS_JSON]).asJsonArray.map { gson.fromJson(it, Subscription::class.java) },
        p[AppPreferences.CURRENT_PROFILE_ID]!!, p[AppPreferences.PROFILES_JSON]!!, p[AppPreferences.SUBSCRIPTIONS_JSON]!!,
    )

    /** A different writer completes after the request is queued, before its edit acquires the store. */
    private class Store(initial: Preferences, private var competingWrite: Preferences? = null) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            competingWrite?.let { data.value = it; competingWrite = null }
            return transform(data.value).also { data.value = it }
        }
    }

    @Test fun removesOnlyOwnedProfilesAndReportsRemovedSelection() = runBlocking {
        val store = Store(state())
        val result = deleteSubscriptionState(store, target.id, ::snapshot, gson)
        assertEquals(listOf(b.id), result.profiles.map { it.id })
        assertEquals(listOf(other.id), result.subscriptions.map { it.id })
        assertEquals(a.id, result.removedSelectedId)
    }

    @Test fun concurrentRefreshAndNewProfilesSurviveDeletion() = runBlocking {
        val added = Profile("c", "Added meanwhile")
        val refreshed = other.copy(name = "Updated", updatedAt = 42)
        val store = Store(state(), state(listOf(a, b.copy(name = "Renamed"), added), listOf(target, refreshed)))
        val result = deleteSubscriptionState(store, target.id, ::snapshot, gson)
        assertEquals(listOf("Renamed", "Added meanwhile"), result.profiles.map { it.name })
        assertEquals(42L, result.subscriptions.single().updatedAt)
    }

    @Test fun newerSelectionMustNotTriggerFallbackToAnUnrelatedProfile() = runBlocking {
        val store = Store(state(), state(selected = b.id))
        val result = deleteSubscriptionState(store, target.id, ::snapshot, gson)
        assertNull(result.removedSelectedId)
        assertEquals(b.id, store.data.value[AppPreferences.CURRENT_PROFILE_ID])
    }

    @Test fun unrelatedUnknownFieldsMustSurviveDelete() = runBlocking {
        val p = state()
        p[AppPreferences.PROFILES_JSON] = JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray.apply {
            get(1).asJsonObject.addProperty("futureField", "keep")
        }.toString()
        p[AppPreferences.SUBSCRIPTIONS_JSON] = JsonParser.parseString(p[AppPreferences.SUBSCRIPTIONS_JSON]).asJsonArray.apply {
            get(1).asJsonObject.addProperty("futureSubField", "keep too")
        }.toString()
        val store = Store(p)
        deleteSubscriptionState(store, target.id, ::snapshot, gson)
        assertEquals("keep", JsonParser.parseString(store.data.value[AppPreferences.PROFILES_JSON]).asJsonArray.single().asJsonObject.get("futureField")?.asString)
        assertEquals("keep too", JsonParser.parseString(store.data.value[AppPreferences.SUBSCRIPTIONS_JSON]).asJsonArray.single().asJsonObject.get("futureSubField")?.asString)
    }

    @Test fun independentMalformedRecordDoesNotBlockDeletingAnUnrelatedSubscription() = runBlocking {
        val p = state()
        p[AppPreferences.PROFILES_JSON] = JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray.apply {
            add(com.google.gson.JsonNull.INSTANCE)
            add(17)
        }.toString()
        val store = Store(p)
        deleteSubscriptionState(store, target.id, ::snapshot, gson)
        val saved = JsonParser.parseString(store.data.value[AppPreferences.PROFILES_JSON]).asJsonArray
        assertEquals(3, saved.size())
        assertEquals(b.id, saved[0].asJsonObject["id"].asString)
        assertTrue(saved[1].isJsonNull)
        assertEquals(17, saved[2].asInt)
    }
}
