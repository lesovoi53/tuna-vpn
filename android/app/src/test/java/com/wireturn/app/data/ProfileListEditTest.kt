package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProfileListEditTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val old = listOf(Profile(id = "a", name = "Old A"), Profile(id = "b", name = "Old B"))
    private val refreshed = listOf(old[0].copy(name = "Refreshed A"), Profile(id = "c", name = "Added C"))
    private fun prefs() = mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(refreshed))

    @Test fun renameDoesNotUndoACompletedSubscriptionRefresh() {
        val p = prefs()
        val result = ProfileListEdit.Rename("a", "My name").commit(p, gson)
        assertEquals(listOf("a", "c"), result.map { it.id })
        assertEquals("My name", result[0].name)
    }
    @Test fun reorderDoesNotRestoreRemovedOrDropAddedProfiles() {
        val result = ProfileListEdit.Reorder(listOf("b", "a", "a")).commit(prefs(), gson)
        assertEquals(listOf("a", "c"), result.map { it.id })
        assertEquals("Refreshed A", result[0].name)
    }
    @Test fun queuedClonesDoNotOverwriteEachOther() {
        val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(old))
        ProfileListEdit.Append(Profile(id = "c", name = "Clone C")).commit(p, gson)
        val result = ProfileListEdit.Append(Profile(id = "d", name = "Clone D")).commit(p, gson)
        assertEquals(listOf("a", "b", "c", "d"), result.map { it.id })
    }
    @Test fun deleteAndReplaceDoNotRevertOtherProfiles() {
        val p = prefs()
        val removed = ProfileListEdit.Remove(setOf("b")).commit(p, gson)
        assertEquals(refreshed, removed)
        val result = ProfileListEdit.Replace(old[0].copy(name = "Edit A")).commit(p, gson)
        assertEquals(listOf("a", "c"), result.map { it.id })
    }
    @Test fun targetedEditsPreserveUnknownFields() {
        val p = prefs()
        val array = JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray
        array[0].asJsonObject.addProperty("futureSetting", "preserve")
        p[AppPreferences.PROFILES_JSON] = array.toString()
        ProfileListEdit.Rename("a", "Renamed").commit(p, gson)
        assertEquals("preserve", JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray[0].asJsonObject.get("futureSetting")?.asString)
    }

    @Test fun importAddsBatchWithoutOverwritingConcurrentRefresh() {
        val p = prefs()
        val added = Profile(id = "d", name = "Imported")
        val result = ProfileListEdit.AppendMany(listOf(added, added)).commit(p, gson)
        assertEquals(refreshed + added, result)
    }

    @Test fun delayedRenameAndReplaceDoNotResurrectRemovedProfile() {
        val p = prefs()
        ProfileListEdit.Rename("b", "Delayed rename").commit(p, gson)
        assertEquals(refreshed, ProfileListEdit.Replace(old[1]).commit(p, gson))
    }

    @Test fun reorderRetainsUnknownFieldsAndNewEntriesInTheirOriginalOrder() {
        val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to """[{"id":"a","future":7},{"id":"b"},{"id":"c"}]""")
        ProfileListEdit.Reorder(listOf("b", "removed", "b", "a")).commit(p, gson)
        val array = JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray
        assertEquals(listOf("b", "a", "c"), array.map { it.asJsonObject["id"].asString })
        assertEquals(7, array[1].asJsonObject["future"].asInt)
    }
}
