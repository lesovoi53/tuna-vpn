package com.wireturn.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.GsonBuilder
import com.wireturn.app.data.kernel.VpnConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SelectedProfileActivationTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val old = Profile("selected", "Old", KernelConfig.Vpn(VpnConfig(
        "vless://11111111-2222-4333-8444-555555555555@192.0.2.4:443?security=tls", "xray")))
    private val updated = old.copy(name = "Refreshed", kernelConfig = KernelConfig.Vpn(VpnConfig(
        "vless://11111111-2222-4333-8444-555555555555@192.0.2.4:8443?security=tls", "xray")))
    private fun state(profiles: List<Profile>) = mutablePreferencesOf(
        AppPreferences.CURRENT_PROFILE_ID to "other",
        AppPreferences.PROFILES_JSON to gson.toJson(profiles))
    private class Store(initial: Preferences, private val concurrent: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(concurrent).also { data.value = it }
    }
    private suspend fun select(store: Store): Profile? = activateSelectedProfileState(store, old,
        { decodeStoredProfiles(it[AppPreferences.PROFILES_JSON]!!, gson, "Profile") }) { p, selected ->
        p[AppPreferences.CURRENT_PROFILE_ID] = selected.id
        p[AppPreferences.ACTIVE_KERNEL_JSON] = gson.toJson(selected.kernelConfig, KernelConfig::class.java)
    }

    @Test fun selectingAnOldRowActivatesRefreshedEndpointInsideTransaction() = runBlocking {
        val store = Store(state(listOf(old)), state(listOf(updated)))
        assertEquals(updated, select(store))
        assertEquals(gson.toJson(updated.kernelConfig, KernelConfig::class.java), store.data.value[AppPreferences.ACTIVE_KERNEL_JSON])
        assertEquals(gson.toJson(listOf(updated)), store.data.value[AppPreferences.PROFILES_JSON])
    }
    @Test fun removedRowIsNotActivated() = runBlocking {
        val store = Store(state(listOf(old)), state(emptyList()))
        assertNull(select(store))
        assertEquals("other", store.data.value[AppPreferences.CURRENT_PROFILE_ID])
        assertNull(store.data.value[AppPreferences.ACTIVE_KERNEL_JSON])
    }
    @Test fun ambiguousRowIsNotActivated() = runBlocking {
        val store = Store(state(listOf(old)), state(listOf(old, updated)))
        assertNull(select(store))
        assertEquals("other", store.data.value[AppPreferences.CURRENT_PROFILE_ID])
    }
    @Test fun explicitSelectionOfPresentRowUpdatesActiveStateOnly() = runBlocking {
        val store = Store(state(listOf(old)), state(listOf(old)))
        assertEquals(old, select(store))
        assertEquals(old.id, store.data.value[AppPreferences.CURRENT_PROFILE_ID])
        assertEquals(gson.toJson(listOf(old)), store.data.value[AppPreferences.PROFILES_JSON])
    }
}
