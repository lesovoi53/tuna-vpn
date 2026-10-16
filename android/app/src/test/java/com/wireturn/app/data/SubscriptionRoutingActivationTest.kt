package com.wireturn.app.data

import androidx.datastore.preferences.core.*
import com.google.gson.GsonBuilder
import com.wireturn.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionRoutingActivationTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val disabled = booleanPreferencesKey("routing_disabled_after_confirmation_v1")
    private fun extracted(mode: String) = AppRoutingSubscription.extract("snell://fixture@192.0.2.1:443?version=5\n" +
        "tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"version":1,"policy":{"Name":"Fixture","RemoteDns":"1.1.1.1","DomesticDns":"8.8.8.8","BlockSites":["domain:ads.example"]},"apps":{"mode":"$mode","packages":["org.not.installed"]}}""".toByteArray()))

    @Test fun initialAddAndRefreshApplyFullRoutingWithoutLeavingGlobalOffSwitch() {
        for (refresh in listOf(false, true)) for (mode in listOf("bypass", "vpn")) {
            val command = extracted(mode)
            val profile = Profile("p", "Fixture", subscriptionId = "s")
            val manual = RoutingRule("domain:personal.example", RouteAction.DIRECT)
            val overrides = SubscriptionRuleOverrides(userRules = listOf(manual)).toJson()
            val previous = Subscription("s", "S", "https://example.invalid/sub", useSubscriptionRouting = false,
                routingOverridesJson = overrides)
            val old = if (refresh) listOf(previous) else emptyList()
            val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(listOf(profile)),
                AppPreferences.SUBSCRIPTIONS_JSON to gson.toJson(old), disabled to true)
            AppRoutingLists.apply(p, AppExclusionsLink.Command(mode == "bypass", setOf("org.personal.app")))
            AppRoutingLists.select(p, mode != "bypass")
            AppRoutingLists.saveActive(p, setOf("org.other.mode"))
            p[AppPreferences.VPN_FILTERING_ENABLED] = false
            val source = command.fullRouting!!.source
            val next = previous.copy(routingSource = source,
                useSubscriptionRouting = ProfileRouting.afterImport(old.firstOrNull(), source))
            val snapshot = SubscriptionImportSnapshot(listOf(profile), old, "p",
                p[AppPreferences.PROFILES_JSON]!!, p[AppPreferences.SUBSCRIPTIONS_JSON]!!)
            // Exact preference transaction used by AppPreferences.commitSubscriptionImport.
            SubscriptionImportCommit.write(p, snapshot, listOf(profile), listOf(next), null, gson, routingSubscriptionId = "s")
            command.command?.let { AppRoutingLists.apply(p, it) }
            assertTrue(p[AppPreferences.VPN_FILTERING_ENABLED] == true)
            assertEquals(mode == "bypass", p[AppPreferences.VPN_BYPASS_MODE])
            assertEquals(setOf("org.personal.app", "org.not.installed"), p[AppPreferences.VPN_EXCLUDED_APPS])
            val resolved = ProfileRouting.resolve(NetworkPreferences.read(p), profile, listOf(next))
            assertTrue(NetworkPolicyResolver.resolve(resolved).routing.rules.any { it.match == "domain:ads.example" })
            assertEquals(manual, NetworkPolicyResolver.resolve(resolved).routing.rules.first())
            assertEquals(overrides, gson.fromJson(p[AppPreferences.SUBSCRIPTIONS_JSON], Array<Subscription>::class.java).single().routingOverridesJson)
            AppRoutingLists.select(p, mode != "bypass")
            assertEquals(setOf("org.other.mode"), p[AppPreferences.VPN_EXCLUDED_APPS])
            assertNotEquals("Subscription routing is still globally disabled (refresh=$refresh)", true, p[disabled])
        }
    }

    @Test fun invalidActivationDoesNotClearGateOrWriteSubscription() {
        val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to "[]", AppPreferences.SUBSCRIPTIONS_JSON to "[]", disabled to true)
        val before = p.toPreferences()
        val snapshot = SubscriptionImportSnapshot(emptyList(), emptyList(), "default", "[]", "[]")
        val invalid = Subscription("s", "S", "https://example.invalid/sub", routingSource = "invalid", useSubscriptionRouting = true)
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionImportCommit.write(p, snapshot, emptyList(), listOf(invalid), null, gson, routingSubscriptionId = "s")
        }
        assertEquals(before, p.toPreferences())
    }
}
