package com.wireturn.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportedProfileActivationTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val profile = Profile("new", "Imported")
    private fun state(selected: String = "old", profiles: List<Profile> = listOf(profile)) = mutablePreferencesOf(
        AppPreferences.CURRENT_PROFILE_ID to selected,
        AppPreferences.PROFILES_JSON to gson.toJson(profiles))
    private class Store(initial: Preferences, private val concurrent: Preferences? = null) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(concurrent ?: data.value).also { data.value = it }
    }
    private suspend fun activate(store: Store): Profile? = activateImportedProfileState(store, "old", profile.id,
        { decodeStoredProfiles(it[AppPreferences.PROFILES_JSON]!!, gson, "Profile") }) { p, selected ->
        p[AppPreferences.CURRENT_PROFILE_ID] = selected.id
    }

    @Test fun pendingImportSelectsFromTheLatestCommittedProfile() = runBlocking {
        val store = Store(state(), state(profiles = listOf(profile.copy(name = "Updated before activation"))))
        assertEquals("Updated before activation", activate(store)?.name)
        assertEquals(profile.id, store.data.value[AppPreferences.CURRENT_PROFILE_ID])
    }
    @Test fun laterUserSelectionIsNeverOverwritten() = runBlocking {
        val store = Store(state(), state(selected = "user-choice"))
        assertNull(activate(store))
        assertEquals("user-choice", store.data.value[AppPreferences.CURRENT_PROFILE_ID])
    }
    @Test fun removedProfileIsNotResurrected() = runBlocking {
        val store = Store(state(), state(profiles = emptyList()))
        assertNull(activate(store))
        assertEquals("old", store.data.value[AppPreferences.CURRENT_PROFILE_ID])
    }
    @Test fun ambiguousDuplicateIdIsNotActivated() = runBlocking {
        val store = Store(state(profiles = listOf(profile, profile.copy(name = "Duplicate"))))
        assertNull(activate(store))
        assertEquals("old", store.data.value[AppPreferences.CURRENT_PROFILE_ID])
    }
}
