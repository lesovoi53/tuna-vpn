package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.wireturn.app.domain.SubscriptionSettingsRegistry
import org.junit.Assert.*
import org.junit.Test

class AutoLaunchAvailabilityTest {
    @Test fun oldEnabledPreferenceCannotStartAnInvisibleConnectionController() {
        val p = mutablePreferencesOf(AppPreferences.AUTO_LAUNCH_ENABLED to true,
            AppPreferences.AUTO_LAUNCH_URL to "https://example.com/check",
            AppPreferences.AUTO_LAUNCH_INTERVAL to 30)
        val original = p.toPreferences()
        val effective = AutoLaunchPreferences.read(p)
        assertFalse(effective.enabled)
        assertEquals("https://example.com/check", effective.checkUrl)
        assertEquals(30, effective.intervalMinutes)
        assertEquals(original, p.toPreferences())
    }

    @Test fun staleUiCannotReenableHiddenAutostart() {
        val p = mutablePreferencesOf()
        AutoLaunchPreferences.write(p, AutoLaunchSettings(enabled = true))
        assertFalse(p[AppPreferences.AUTO_LAUNCH_ENABLED]!!)
        assertFalse(AutoLaunchPreferences.read(p).enabled)
    }

    @Test fun subscriptionAndUndoCannotOfferOrApplyHiddenAutostart() {
        for (path in listOf("connection.autoLaunch.enabled", "connection.autoLaunch.checkUrl", "connection.autoLaunch.intervalMinutes")) {
            assertTrue(path, path in SubscriptionSettingsRegistry.hidden)
            assertFalse(path, SubscriptionSettingsRegistry.isApplicable(path))
        }
        assertTrue(SubscriptionSettingsRegistry.isApplicable("connection.waitForNetwork"))
        assertFalse(SubscriptionSettingsRegistry.isApplicable("vpn.excludedApps"))
    }
}
