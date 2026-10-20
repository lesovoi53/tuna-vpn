package com.wireturn.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit

/** Import completion must not undo a later selection or resurrect a removed record. */
internal suspend fun activateImportedProfileState(
    store: DataStore<Preferences>, expectedCurrentId: String, id: String,
    readProfiles: (Preferences) -> List<Profile>,
    write: (MutablePreferences, Profile) -> Unit,
): Profile? {
    var activated: Profile? = null
    store.edit { preferences ->
        if (preferences[MasterDnsGroups.activeKey] == null &&
            (preferences[AppPreferences.CURRENT_PROFILE_ID] ?: "default") == expectedCurrentId) {
            readProfiles(preferences).singleOrNull { it.id == id }?.let { profile ->
                write(preferences, profile)
                activated = profile
            }
        }
    }
    return activated
}
