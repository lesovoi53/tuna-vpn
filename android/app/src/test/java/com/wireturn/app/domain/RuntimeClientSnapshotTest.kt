package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsPoolSelection
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RuntimeClientSnapshotTest {
    private fun profile(id: String, subscription: String? = null) = Profile(id, "DNS $id",
        KernelConfig.MasterDns(MasterDnsConfig(domains = listOf("$id.example.invalid"),
            key = "synthetic-$id", resolvers = "192.0.2.53")), subscriptionId = subscription)
    private fun config(profile: Profile) = (profile.kernelConfig as KernelConfig.MasterDns).config
    private fun client(anchor: Profile) = ClientConfig(socksUser = "synthetic-user", socksPass = "synthetic-pass",
        kernelConfig = KernelConfig.MasterDns(config(anchor).copy(localPool = MasterDnsPoolSelection(listOf("a", "b")))))
    private fun snapshot(
        profiles: List<Profile> = listOf(profile("a"), profile("b")),
        subscriptions: List<Subscription> = emptyList(),
        shared: SharedNetworkSettings = SharedNetworkSettings(),
        active: ClientConfig = client(profiles.first()),
    ) = RuntimeClientSnapshot.resolve(active, "a", profiles, subscriptions, shared)
    private fun source(name: String) = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(
        """{"Name":"$name","RemoteDNSType":"TCP","RemoteDns":"192.0.2.53","DomesticDNSType":"UDP","DomesticDns":"192.0.2.54","BlockSites":["domain:blocked.example"]}""".toByteArray())
    private fun subscription(source: String? = null) = Subscription("s", "Synthetic", "https://example.invalid/sub",
        routingSource = source, useSubscriptionRouting = !source.isNullOrBlank())

    @Test fun activeAnchorEditsWinOverStoredProfileAndMemberEditsChangeRuntimePlan() {
        val anchor = profile("a")
        val other = profile("b")
        val active = client(anchor).copy(kernelConfig = KernelConfig.MasterDns(config(anchor).copy(
            key = "unsaved-anchor-key", localPool = MasterDnsPoolSelection(listOf("a", "b")))))
        val before = snapshot(listOf(anchor, other), active = active)
        assertNull(before.error)
        assertEquals("unsaved-anchor-key", before.config.masterDnsPoolPlan!!.members.first().config.key)
        val edited = other.copy(kernelConfig = KernelConfig.MasterDns(config(other).copy(key = "updated-member-key")))
        val after = snapshot(listOf(anchor, edited), active = active)
        assertEquals(before.config.kernelConfig, after.config.kernelConfig)
        assertNotEquals(before.config.masterDnsPoolPlan, after.config.masterDnsPoolPlan)
        assertEquals("updated-member-key", after.config.masterDnsPoolPlan!!.members.last().config.key)
    }

    @Test fun removedMemberIsAnErrorAndNeverShrinksToAnchor() {
        val anchor = profile("a")
        val result = snapshot(listOf(anchor))
        assertTrue(result.error.orEmpty().contains("удалён"))
        assertNull(result.config.masterDnsPoolPlan)
        assertTrue(runCatching { result.runtimeConfig() }.isFailure)
        assertEquals(listOf("a", "b"), (result.config.kernelConfig as KernelConfig.MasterDns).config.localPool!!.memberIds)
    }

    @Test fun deletedOrAmbiguousAnchorAndSubscriptionFailClosed() {
        val anchor = profile("a", "s")
        val other = profile("b", "s")
        val active = client(anchor)
        for (members in listOf(listOf(other), listOf(anchor, anchor, other))) {
            assertNotNull(snapshot(members, listOf(subscription()), active = active).error)
        }
        for (subscriptions in listOf(emptyList(), listOf(subscription(), subscription()))) {
            assertNotNull(snapshot(listOf(anchor, other), subscriptions, active = active).error)
        }
    }

    @Test fun subscriptionPolicyAndPersonalOverridesAreResolvedForPool() {
        val members = listOf(profile("a", "s"), profile("b", "s"))
        val rule = RoutingRule("domain:personal.example", RouteAction.DIRECT)
        val sub = subscription(source("One")).copy(routingOverridesJson =
            SubscriptionRuleOverrides(userRules = listOf(rule)).toJson())
        val shared = SharedNetworkSettings(dns = NetworkDnsSettings(bootstrap = "tcp://198.51.100.53:53"))
        val result = snapshot(members, listOf(sub), shared)
        assertNull(result.error)
        assertEquals(sub.routingSource, result.poolRouting!!.routing.subscriptionSource)
        assertEquals(shared.dns.bootstrap, result.poolRouting.dns.bootstrap)
        assertTrue(result.poolRouting.routing.policyJson.contains("personal.example"))
        assertEquals(ProfileRouting.resolve(shared, members.first(), listOf(sub)), result.poolRouting)
    }

    @Test fun subscriptionWithoutRulesAndOwnProfilesUseAppRouting() {
        val shared = SharedNetworkSettings(routing = NetworkRoutingSettings(
            policyJson = RoutingPolicy(rules = listOf(RoutingRule("domain:own.example", RouteAction.DIRECT))).toJson()))
        val own = snapshot(shared = shared)
        val subscribed = snapshot(listOf(profile("a", "s"), profile("b", "s")), listOf(subscription()), shared)
        assertNull(own.error)
        assertNull(subscribed.error)
        assertEquals(shared, own.poolRouting)
        assertEquals(shared, subscribed.poolRouting)
    }

    @Test fun routingRefreshIsObservedEvenIfNativeMemberConfigurationIsUnchanged() {
        val members = listOf(profile("a", "s"), profile("b", "s"))
        val before = snapshot(members, listOf(subscription(source("Before"))))
        val after = snapshot(members, listOf(subscription(source("After"))))
        assertNull(before.error)
        assertNull(after.error)
        assertEquals(before.config.masterDnsPoolPlan, after.config.masterDnsPoolPlan)
        assertNotEquals(before, after)
        val broken = snapshot(members, listOf(subscription("invalid-routing")))
        assertNotNull(broken.error)
        assertNull(broken.config.masterDnsPoolPlan)
    }

    @Test fun mixedOwnersAndNestedPoolsNeverRun() {
        for (other in listOf(profile("b", "other"), profile("b", null))) {
            assertNotNull(snapshot(listOf(profile("a", "s"), other),
                listOf(subscription(), subscription().copy(id = "other"))).error)
        }
        val nested = profile("b").copy(kernelConfig = client(profile("b")).kernelConfig)
        assertTrue(snapshot(listOf(profile("a"), nested)).error.orEmpty().contains("Вложенные"))
    }

    @Test fun poolRequiresAuthenticatedLoopbackAndOrdinaryConfigIsUnaffected() {
        val active = client(profile("a"))
        assertNotNull(snapshot(active = active.copy(isSocksAuthEnabled = false)).error)
        assertNotNull(snapshot(active = active.copy(socksAddr = "0.0.0.0:2081")).error)
        val ordinary = active.copy(kernelConfig = profile("a").kernelConfig, isSocksAuthEnabled = false)
        val result = RuntimeClientSnapshot.resolve(ordinary, "missing", emptyList(), emptyList(), SharedNetworkSettings())
        assertNull(result.error)
        assertEquals(ordinary, result.config)
    }

    @Test fun resolvedCredentialsAreNeverSerializedOrAcceptedFromJson() {
        val resolved = snapshot().config
        assertNotNull(resolved.masterDnsPoolPlan)
        val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
        val json = gson.toJson(resolved)
        assertFalse(json.contains("masterDnsPoolPlan"))
        assertFalse(json.contains("synthetic-b"))
        val injected = JsonParser.parseString(json).asJsonObject.apply {
            add("masterDnsPoolPlan", gson.toJsonTree(resolved.masterDnsPoolPlan))
        }
        assertNull(gson.fromJson(injected, ClientConfig::class.java).masterDnsPoolPlan)
    }
}
