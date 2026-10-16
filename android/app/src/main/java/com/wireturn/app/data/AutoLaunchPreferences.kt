package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

internal object AutoLaunchPreferences {
    fun read(p: Preferences) = AutoLaunchSettings(
        // The feature is hidden by product policy. An older saved value or an
        // imported preference must not leave an invisible start/stop job alive.
        false,
        p[AppPreferences.AUTO_LAUNCH_URL] ?: "https://www.google.com",
        p[AppPreferences.AUTO_LAUNCH_INTERVAL] ?: 15,
    )

    fun write(p: MutablePreferences, value: AutoLaunchSettings) {
        p[AppPreferences.AUTO_LAUNCH_ENABLED] = false
        p[AppPreferences.AUTO_LAUNCH_URL] = value.checkUrl
        p[AppPreferences.AUTO_LAUNCH_INTERVAL] = value.intervalMinutes
    }
}
