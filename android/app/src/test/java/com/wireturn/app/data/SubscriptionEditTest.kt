package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SubscriptionEditTest {
    private val gson = Gson()
    private val old = Subscription(id = "s", name = "Subscription", url = "https://example.com/sub")
    private val fresh = old.copy(routingSource = "new routing", updatedAt = 42, bytesUsed = 123, tunaDocument = "new document")
    private fun preferences() = mutablePreferencesOf(
        AppPreferences.SUBSCRIPTIONS_JSON to gson.toJson(listOf(fresh, old.copy(id = "added"))),
        AppPreferences.PROFILES_JSON to """[{"id":"p","subscriptionId":"s"}]"""
    )
    @Test fun selectingServerKeepsRefreshedRoutingAndNewSubscriptions() {
        val p = preferences()
        SubscriptionEdit.ActiveProfile("s", "p").commit(p)
        val saved = JsonParser.parseString(p[AppPreferences.SUBSCRIPTIONS_JSON]).asJsonArray
        assertEquals(2, saved.size())
        assertEquals(fresh.copy(activeProfileId = "p"), gson.fromJson(saved[0], Subscription::class.java))
    }
    @Test fun savingVisibleSettingsDoesNotRollBackServerMetadata() {
        val p = preferences()
        SubscriptionEdit.Settings(old.copy(autoUpdate = true, updateIntervalMinutes = 60)).commit(p)
        val saved = JsonParser.parseString(p[AppPreferences.SUBSCRIPTIONS_JSON]).asJsonArray
        assertEquals(2, saved.size())
        assertEquals(fresh.copy(autoUpdate = true, updateIntervalMinutes = 60), gson.fromJson(saved[0], Subscription::class.java))
    }
    @Test fun staleSelectionCannotResurrectRemovedSubscriptionOrSelectRemovedProfile() {
        val p = preferences()
        val before = p.toPreferences()
        SubscriptionEdit.ActiveProfile("s", "removed").commit(p)
        assertEquals(before, p.toPreferences())
        p[AppPreferences.SUBSCRIPTIONS_JSON] = "[]"
        SubscriptionEdit.Settings(old).commit(p)
        assertEquals("[]", p[AppPreferences.SUBSCRIPTIONS_JSON])
    }
}
