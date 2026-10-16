package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.wireturn.app.domain.AppExclusionsLink

/** Legacy active list stays authoritative; inactive mode is saved independently. */
internal object AppRoutingLists {
    private val bypassList = stringSetPreferencesKey("app_routing_bypass_packages_v1")
    private val vpnList = stringSetPreferencesKey("app_routing_vpn_packages_v1")
    private fun key(bypass: Boolean) = if (bypass) bypassList else vpnList

    fun select(p: MutablePreferences, bypass: Boolean) {
        val oldMode = p[AppPreferences.VPN_BYPASS_MODE] ?: true
        val active = p[AppPreferences.VPN_EXCLUDED_APPS].orEmpty()
        p[key(oldMode)] = active
        if (oldMode != bypass) p[AppPreferences.VPN_EXCLUDED_APPS] = p[key(bypass)].orEmpty()
        p[AppPreferences.VPN_BYPASS_MODE] = bypass
    }

    fun saveActive(p: MutablePreferences, packages: Set<String>) {
        p[AppPreferences.VPN_EXCLUDED_APPS] = packages
        p[key(p[AppPreferences.VPN_BYPASS_MODE] ?: true)] = packages
    }

    fun apply(p: MutablePreferences, command: AppExclusionsLink.Command) {
        select(p, command.bypass)
        saveActive(p, p[AppPreferences.VPN_EXCLUDED_APPS].orEmpty() + command.packages)
        p[AppPreferences.VPN_FILTERING_ENABLED] = true
    }
}
