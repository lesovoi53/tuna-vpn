package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import com.google.gson.JsonParser

internal sealed interface SubscriptionEdit {
    data class ActiveProfile(val id: String, val profileId: String) : SubscriptionEdit
    data class Settings(val value: Subscription) : SubscriptionEdit

    fun commit(p: MutablePreferences) {
        val current = JsonParser.parseString(p[AppPreferences.SUBSCRIPTIONS_JSON] ?: "[]").asJsonArray
        val id = when (this) { is ActiveProfile -> id; is Settings -> value.id }
        val entry = current.firstOrNull { it.asJsonObject.get("id")?.asString == id }?.asJsonObject ?: return
        when (this) {
            is ActiveProfile -> {
                val profiles = JsonParser.parseString(p[AppPreferences.PROFILES_JSON] ?: "[]").asJsonArray
                if (profiles.none {
                    it.asJsonObject.get("id")?.asString == profileId &&
                        it.asJsonObject.get("subscriptionId")?.takeUnless { it.isJsonNull }?.asString == id
                }) return
                entry.addProperty("activeProfileId", profileId)
            }
            is Settings -> {
                // Only fields editable in SubscriptionConfigActivity; metadata and routing
                // can have changed while the editor was open or its save was queued.
                entry.addProperty("url", value.url)
                entry.addProperty("autoUpdate", value.autoUpdate)
                entry.addProperty("updateIntervalMinutes", value.updateIntervalMinutes)
                entry.addProperty("onlyUpdateIfSelected", value.onlyUpdateIfSelected)
                entry.addProperty("requireTunnelForUpdate", value.requireTunnelForUpdate)
            }
        }
        p[AppPreferences.SUBSCRIPTIONS_JSON] = current.toString()
    }
}
