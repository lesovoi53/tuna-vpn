package com.wireturn.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit

/** Selection may wait behind a subscription transaction after the row was rendered. */
internal suspend fun activateSelectedProfileState(
    store: DataStore<Preferences>, selectedSnapshot: Profile,
    readProfiles: (Preferences) -> List<Profile>,
    write: (MutablePreferences, Profile) -> Unit,
): Profile? {
    var activated: Profile? = null
    store.edit { preferences ->
        readProfiles(preferences).singleOrNull { it.id == selectedSnapshot.id }?.let { latest ->
            write(preferences, latest)
            activated = latest
        }
    }
    return activated
}
