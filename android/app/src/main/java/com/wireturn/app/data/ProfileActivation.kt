package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import com.google.gson.Gson

/** Activates endpoint/chain only. Global DNS, rules, router and routing consent stay intact. */
internal object ProfileActivation {
    fun write(p: MutablePreferences, id: String, profile: Profile, snapshot: KernelSnapshot, gson: Gson) {
            p[AppPreferences.CURRENT_PROFILE_ID] = id
            p[AppPreferences.ACTIVE_KERNEL_JSON] = gson.toJson(snapshot)
            p[AppPreferences.ACTIVE_XRAY_CONFIG_TYPE] = profile.xrayProtocol.name
            p[AppPreferences.ACTIVE_XRAY_ENABLED] = profile.xrayEnabled
            p[AppPreferences.ACTIVE_WG_JSON] = gson.toJson(profile.wgConfig)
            p[AppPreferences.ACTIVE_VLESS_JSON] = gson.toJson(profile.vlessConfig)
    }
    fun forGroup(member: Profile, anchor: Profile): Profile = member.copy(xrayEnabled = anchor.xrayEnabled,
        xrayProtocol = anchor.xrayProtocol, wgConfig = anchor.wgConfig, vlessConfig = anchor.vlessConfig)
}
