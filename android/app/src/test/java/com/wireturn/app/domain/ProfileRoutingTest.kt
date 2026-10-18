package com.wireturn.app.domain

import com.google.gson.Gson
import com.wireturn.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ProfileRoutingTest {
    private fun source(name: String = "A", onadd: Boolean = true) = "happ://routing/${if (onadd) "onadd" else "add"}/" + Base64.getEncoder().encodeToString(
        """{"Name":"$name","RemoteDNSType":"TCP","RemoteDns":"192.0.2.53","DomesticDNSType":"UDP","DomesticDns":"192.0.2.54","BlockSites":["domain:blocked.example"]}""".toByteArray())
    private fun sub(id: String, enabled: Boolean? = true) = Subscription(id, id, "https://example.com/$id", routingSource = source(id), routingRecommended = true, useSubscriptionRouting = enabled)
    private val common = SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = "disabled", bootstrap = "tcp://192.0.2.60:53"),
        routing = NetworkRoutingSettings(legacyDirect = "domain:local.example"))
    private fun profile(sub: String?) = Profile("p", "Server", subscriptionId = sub)

    @Test fun selectedSubscriptionOwnsItsRoutingAndDnsOnly() {
        val a = sub("a"); val b = sub("b")
        for (s in listOf(a, b)) {
            val resolved = ProfileRouting.resolve(common, profile(s.id), listOf(a, b))
            assertEquals(s.routingSource, resolved.routing.subscriptionSource)
            assertEquals("", resolved.dns.splitDnsJson)
            assertEquals(common.dns.bootstrap, resolved.dns.bootstrap)
        }
        assertEquals("disabled", common.dns.splitDnsJson)
        assertEquals("", common.routing.subscriptionSource)
    }
    @Test fun standaloneDisabledMissingAndDeletedSubscriptionUseCommon() {
        for (p in listOf(profile(null), profile("disabled"), profile("deleted"), null))
            assertEquals(common, ProfileRouting.resolve(common, p, listOf(sub("disabled", false))))
    }
    @Test fun everyReceivedPolicyAutomaticallyEnablesNewAndPreviouslyDisabledSubscriptions() {
        assertTrue(ProfileRouting.afterImport(null, source()))
        assertTrue(ProfileRouting.afterImport(null, source(onadd = false)))
        assertTrue(ProfileRouting.afterImport(sub("a", false), source()))
        assertTrue(ProfileRouting.afterImport(sub("a", true), source(onadd = false)))
    }
    @Test fun rulesAddedAfterInitialImportActivateForThatSubscriptionOnly() {
        val initial = Subscription("a", "A", "https://example.com/a",
            routingSource = "", useSubscriptionRouting = ProfileRouting.afterImport(null, ""))
        assertFalse(ProfileRouting.enabled(initial))
        val received = source()
        val updated = initial.copy(routingSource = received, routingRecommended = true,
            useSubscriptionRouting = ProfileRouting.afterImport(initial, received))
        assertTrue(ProfileRouting.enabled(updated))
        assertEquals(received, ProfileRouting.resolve(common, profile("a"), listOf(updated)).routing.subscriptionSource)
        assertEquals(common, ProfileRouting.resolve(common, profile(null), listOf(updated)))
        assertTrue(ProfileRouting.afterImport(initial, source(onadd = false)))
    }

    @Test fun oldSubscriptionMigratesAndRefreshOverridesStoredDisabledChoice() {
        val old = Gson().fromJson("""{"id":"a","name":"a","url":"https://example.com","routingRecommended":true,"routingSource":${Gson().toJson(source())}}""", Subscription::class.java)
        assertNull(old.useSubscriptionRouting)
        assertTrue(ProfileRouting.enabled(old))
        val disabled = Gson().fromJson(Gson().toJson(old.copy(useSubscriptionRouting = false)), Subscription::class.java)
        assertTrue(ProfileRouting.afterImport(disabled, source()))
    }
    @Test fun headerRecommendationIsNotRequiredAndRefreshEnablesRouting() {
        val link = source(onadd = false)
        val policy = SubscriptionPolicyParser.fromHeaders(mapOf("Routing" to listOf(link), "Routing-Enable" to listOf("true")))!!
        assertTrue(ProfileRouting.afterImport(null, link, policy.enabled))
        val withoutRules = Subscription("a", "A", "https://example.com/a", routingSource = "", useSubscriptionRouting = false)
        assertTrue(ProfileRouting.afterImport(withoutRules, link, policy.enabled))
        assertTrue(ProfileRouting.afterImport(sub("a", false), link, policy.enabled))
        assertTrue(ProfileRouting.afterImport(sub("a", true), link, false))
        assertFalse(ProfileRouting.afterImport(null, "", true))
    }
    @Test fun unavailableEnabledPolicyCannotSilentlyFallBackToDirect() {
        for (source in listOf("", "broken")) assertTrue(runCatching {
            ProfileRouting.resolve(common, profile("a"), listOf(sub("a").copy(routingSource = source)))
        }.isFailure)
    }
    @Test fun oldOnaddGlobalCopyIsDetachedWithoutErasingCustomDns() {
        val s = sub("a", null)
        val legacy = common.copy(routing = common.routing.copy(subscriptionSource = s.routingSource!!))
        assertEquals(common, ProfileRouting.common(legacy, listOf(s)))
        val copied = legacy.copy(dns = legacy.dns.copy(splitDnsJson = SplitDnsSettings.fromPolicy(SubscriptionPolicyParser.parseHapp(s.routingSource)).toJson()))
        assertEquals("", ProfileRouting.common(copied, listOf(s)).dns.splitDnsJson)
        assertEquals("", ProfileRouting.resolve(ProfileRouting.common(copied, listOf(s)), profile(null), listOf(s)).routing.subscriptionSource)
    }
    @Test fun preEmergencyDefaultCopyIsRemovedButRealRecoveryOverridesSurvive() {
        val subscription = sub("a")
        val source = subscription.routingSource!!
        val policy = SubscriptionPolicyParser.parseHapp(source)
        val oldCopy = policy.dns ?: SplitDnsSettings(policy.remote, policy.domestic, policy.hosts)
        val legacy = common.copy(routing = common.routing.copy(subscriptionSource = source),
            dns = common.dns.copy(splitDnsJson = oldCopy.toJson()))
        assertEquals("", ProfileRouting.common(legacy, listOf(subscription)).dns.splitDnsJson)
        val overrides = listOf(oldCopy.copy(remoteEmergency = DnsRecoverySettings("disabled")),
            oldCopy.copy(remoteEmergency = DnsRecoverySettings("system")),
            oldCopy.copy(bootstrap = DnsRecoverySettings("system")),
            oldCopy.copy(timeoutMs = 2000))
        for (override in overrides) {
            val settings = legacy.copy(dns = legacy.dns.copy(splitDnsJson = override.toJson()))
            val migrated = ProfileRouting.common(settings, listOf(subscription))
            assertEquals(override.toJson(), migrated.dns.splitDnsJson)
            assertEquals("", migrated.routing.subscriptionSource)
        }
    }
    @Test fun routerIsAlwaysXrayAndProtocolSelectionIsNotInvolved() {
        assertEquals(NetworkRouter.XRAY, ProfileRouting.resolve(common.copy(router = NetworkRouter.SING_BOX_EXTENDED), profile(null), emptyList()).router)
    }
}

