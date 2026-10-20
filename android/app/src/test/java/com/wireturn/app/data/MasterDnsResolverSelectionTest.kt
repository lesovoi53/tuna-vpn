package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.domain.SubscriptionProfileMerge
import org.junit.Assert.*
import org.junit.Test

class MasterDnsResolverSelectionTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val config = MasterDnsConfig(domains = listOf("dns.example.invalid"), key = "synthetic-key", resolvers = "192.0.2.53")
    private val profile = Profile("a", "DNS", KernelConfig.MasterDns(config), subscriptionId = "sub",
        subscriptionSourceId = "source-a", subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(KernelConfig.MasterDns(config)))

    @Test fun transactionUpdatesOnlySelectionAndSelectedKernelSnapshot() {
        val other = profile.copy(id = "b", subscriptionSourceId = "source-b")
        val array = JsonParser.parseString(gson.toJson(listOf(profile, other))).asJsonArray
        array[0].asJsonObject.addProperty("future_profile_field", "preserve")
        array[0].asJsonObject.getAsJsonObject("kernelConfig").getAsJsonObject("config").addProperty("future_config_field", 42)
        val untouched = array[1].deepCopy()
        val baseline = array[0].asJsonObject["subscriptionKernelBaseline"].deepCopy()
        val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to array.toString(), AppPreferences.CURRENT_PROFILE_ID to "a")
        MasterDnsResolverSelection.commit(p, listOf(profile, other), profile, "198.51.100.53", true, gson)
        val saved = JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray
        assertEquals(untouched, saved[1])
        assertEquals(baseline, saved[0].asJsonObject["subscriptionKernelBaseline"])
        assertEquals("preserve", saved[0].asJsonObject["future_profile_field"].asString)
        assertEquals(42, saved[0].asJsonObject.getAsJsonObject("kernelConfig").getAsJsonObject("config")["future_config_field"].asInt)
        val active = gson.fromJson(p[AppPreferences.ACTIVE_KERNEL_JSON], KernelSnapshot::class.java)
        assertEquals(listOf("198.51.100.53"), active.masterdns!!.resolverList())
        assertEquals(listOf("192.0.2.53"), active.masterdns!!.profileResolverList())
    }

    @Test fun staleScanOrDeletedProfileCannotOverwriteSubscriptionRefresh() {
        val refreshed = profile.copy(kernelConfig = KernelConfig.MasterDns(config.copy(resolvers = "192.0.2.54")))
        for (profiles in listOf(listOf(refreshed), emptyList())) {
            val original = gson.toJson(profiles)
            val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to original, AppPreferences.CURRENT_PROFILE_ID to "a")
            assertThrows(IllegalStateException::class.java) {
                MasterDnsResolverSelection.commit(p, profiles, profile, "198.51.100.53", true, gson)
            }
            assertEquals(original, p[AppPreferences.PROFILES_JSON])
            assertNull(p[AppPreferences.ACTIVE_KERNEL_JSON])
        }
    }

    @Test fun unselectedProfileSaveDoesNotActivateItOrChangeRunningSnapshot() {
        val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(listOf(profile)),
            AppPreferences.CURRENT_PROFILE_ID to "other", AppPreferences.ACTIVE_KERNEL_JSON to "unchanged")
        MasterDnsResolverSelection.commit(p, listOf(profile), profile, "198.51.100.53", false, gson)
        assertEquals("other", p[AppPreferences.CURRENT_PROFILE_ID])
        assertEquals("unchanged", p[AppPreferences.ACTIVE_KERNEL_JSON])
        val saved = gson.fromJson(JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray[0], Profile::class.java)
        assertEquals("198.51.100.53", (saved.kernelConfig as KernelConfig.MasterDns).config.localResolvers)
        assertEquals(config.resolverList(), (saved.kernelConfig as KernelConfig.MasterDns).config.resolverList())
    }
}
