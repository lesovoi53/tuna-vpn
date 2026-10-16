package com.wireturn.app.domain

import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.AppRoutingLists
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class AppExclusionsLinkTest {
    @Test fun exclusionCommandAutomaticallySelectsBypassMode() {
        val prefs = mutablePreferencesOf(AppPreferences.VPN_BYPASS_MODE to false,
            AppPreferences.VPN_EXCLUDED_APPS to setOf("org.example.keep"))
        AppRoutingLists.apply(prefs, AppExclusionsLink.command("tuna://app-exclusions/v1?packages=com.android.chrome"))
        assertEquals(true, prefs[AppPreferences.VPN_BYPASS_MODE])
        assertEquals(setOf("com.android.chrome"), prefs[AppPreferences.VPN_EXCLUDED_APPS])
        AppRoutingLists.select(prefs, false)
        assertEquals(setOf("org.example.keep"), prefs[AppPreferences.VPN_EXCLUDED_APPS])
    }
    private val chrome = "com.android.chrome"
    private val link = "tuna://app-exclusions/v1?packages=$chrome"

    @Test fun chromeLinkAndPreview() {
        assertEquals(setOf(chrome), AppExclusionsLink.parse(link))
        assertTrue(previewDeepLink(link) is DeepLinkPreview.AppExclusions)
    }

    @Test fun additiveAndIdempotentWithoutOtherSettingsChanges() {
        val prefs = mutablePreferencesOf(AppPreferences.VPN_ENABLED to false,
            AppPreferences.VPN_EXCLUDED_APPS to setOf("org.example.existing"))
        repeat(2) { AppRoutingLists.apply(prefs, AppExclusionsLink.command(link)) }
        assertEquals(setOf("org.example.existing", chrome), prefs[AppPreferences.VPN_EXCLUDED_APPS])
        assertEquals(false, prefs[AppPreferences.VPN_ENABLED])
    }

    @Test fun absentAppsAreRetainedAndDuplicatesCollapse() {
        assertEquals(setOf(chrome, "org.example.notinstalled"),
            AppExclusionsLink.parse("$link,$chrome,org.example.notinstalled"))
        val prefs = mutablePreferencesOf()
        AppRoutingLists.apply(prefs, AppExclusionsLink.command("$link,org.example.notinstalled&mode=vpn"))
        AppRoutingLists.select(prefs, true)
        AppRoutingLists.select(prefs, false)
        assertEquals(setOf(chrome, "org.example.notinstalled"), prefs[AppPreferences.VPN_EXCLUDED_APPS])
    }

    @Test fun bypassFilteringBecomesEnabled() {
        val prefs = mutablePreferencesOf(AppPreferences.VPN_FILTERING_ENABLED to false)
        AppRoutingLists.apply(prefs, AppExclusionsLink.command(link))
        assertEquals(true, prefs[AppPreferences.VPN_FILTERING_ENABLED])
    }

    @Test fun bothListsSurviveSwitchesAndImportsOnlyAddToTarget() {
        val prefs = mutablePreferencesOf(AppPreferences.VPN_EXCLUDED_APPS to setOf("org.example.bypass"))
        AppRoutingLists.select(prefs, false)
        AppRoutingLists.saveActive(prefs, setOf("org.example.vpn"))
        AppRoutingLists.apply(prefs, AppExclusionsLink.command(link))
        assertEquals(setOf("org.example.bypass", chrome), prefs[AppPreferences.VPN_EXCLUDED_APPS])
        AppRoutingLists.apply(prefs, AppExclusionsLink.command("$link&mode=vpn"))
        assertEquals(false, prefs[AppPreferences.VPN_BYPASS_MODE])
        assertEquals(setOf("org.example.vpn", chrome), prefs[AppPreferences.VPN_EXCLUDED_APPS])
        AppRoutingLists.select(prefs, true)
        assertEquals(setOf("org.example.bypass", chrome), prefs[AppPreferences.VPN_EXCLUDED_APPS])
    }

    @Test fun inactiveAllowlistIsPreservedWhileBypassIsActivated() {
        val prefs = mutablePreferencesOf(AppPreferences.VPN_BYPASS_MODE to false,
            AppPreferences.VPN_FILTERING_ENABLED to false,
            AppPreferences.VPN_EXCLUDED_APPS to setOf("org.example.keep"))
        AppRoutingLists.apply(prefs, AppExclusionsLink.command(link))
        assertEquals(true, prefs[AppPreferences.VPN_BYPASS_MODE])
        assertEquals(true, prefs[AppPreferences.VPN_FILTERING_ENABLED])
        AppRoutingLists.select(prefs, false)
        assertEquals(setOf("org.example.keep"), prefs[AppPreferences.VPN_EXCLUDED_APPS])
    }

    @Test fun malformedCommandsAreRejected() {
        for (bad in listOf(
            "tuna://app-exclusions/v2?packages=$chrome", "$link&reset=true", "$link#fragment",
            "$link&mode=wrong", "$link&mode=vpn&mode=bypass",
            "tuna://app-exclusions/v1?packages=", "$link,", "$link,*", "$link&packages=org.example.app",
            "tuna://app-exclusions/v1?packages=com%2Eandroid.chrome",
            "tuna://app-exclusions/v1?packages=" + List(101) { chrome }.joinToString(","),
            "tuna://app-exclusions/v1?packages=" + "x".repeat(33_000)
        )) {
            assertTrue(bad.take(100), runCatching { AppExclusionsLink.parse(bad) }.isFailure)
            assertEquals(DeepLinkPreview.Invalid, previewDeepLink(bad))
        }
    }
}
