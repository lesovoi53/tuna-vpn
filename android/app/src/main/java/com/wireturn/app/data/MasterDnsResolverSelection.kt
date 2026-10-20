package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import com.google.gson.Gson
import com.google.gson.JsonParser

/** Called inside the DataStore transaction with profiles read from that same state. */
internal object MasterDnsResolverSelection {
    fun commit(p: MutablePreferences, profiles: List<Profile>, expected: Profile,
               localResolvers: String?, useLocalResolvers: Boolean, gson: Gson) {
        val current = profiles.singleOrNull { it.id == expected.id }
        check(current != null && current.kernelConfig == expected.kernelConfig) {
            "Профиль изменился. Откройте выбор резолверов заново."
        }
        val kernel = current.kernelConfig as? KernelConfig.MasterDns
            ?: error("Профиль больше не использует MasterDnsVPN")
        val config = kernel.config.copy(localResolvers = localResolvers, useLocalResolvers = useLocalResolvers)
        config.validate()
        val replacement = current.copy(kernelConfig = KernelConfig.MasterDns(config))
        val array = JsonParser.parseString(p[AppPreferences.PROFILES_JSON] ?: "[]").asJsonArray
        val item = array.single { it.asJsonObject.get("id")?.asString == current.id }.asJsonObject
        val stored = item.getAsJsonObject("kernelConfig").getAsJsonObject("config")
        // Preserve subscription baselines and unknown fields, including on this profile.
        if (localResolvers == null) stored.remove("localResolvers")
        else stored.addProperty("localResolvers", localResolvers)
        stored.addProperty("useLocalResolvers", useLocalResolvers)
        p[AppPreferences.PROFILES_JSON] = array.toString()
        if (p[AppPreferences.CURRENT_PROFILE_ID] == current.id) {
            ProfileActivation.write(p, current.id, replacement,
                KernelSnapshot(variant = KernelVariant.MASTERDNS.name, masterdns = config), gson)
        }
    }
}
