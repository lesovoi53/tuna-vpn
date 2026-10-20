package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.MasterDnsConfig
import org.junit.Assert.*
import org.junit.Test

class MasterDnsGroupRuntimeTest {
    private fun profile(id: String, sub: String? = null) = Profile(id, "DNS $id",
        KernelConfig.MasterDns(MasterDnsConfig(domains = listOf("$id.example.invalid"),
            key = "synthetic-$id", resolvers = "192.0.2.53")), subscriptionId = sub)
    private val group = MasterDnsGroup("g", "Моя группа", listOf("a", "b"))
    private val client = ClientConfig(socksUser = "test", socksPass = "test",
        kernelConfig = profile("a").kernelConfig)
    private fun resolve(profiles: List<Profile> = listOf(profile("a"), profile("b")),
                        groups: List<MasterDnsGroup> = listOf(group), active: String? = "g",
                        subscriptions: List<Subscription> = emptyList(), selected: String = "a") =
        RuntimeClientSnapshot.resolve(client, selected, profiles, subscriptions, SharedNetworkSettings(),
            masterDnsGroups = groups, activeMasterDnsGroupId = active)

    @Test fun groupUsesCurrentRealMembersAndLeavesIndividualProfilesStandalone() {
        val profiles = listOf(profile("a"), profile("b"))
        val result = resolve(profiles)
        assertNull(result.error)
        assertEquals("g", result.masterDnsGroupId)
        assertEquals("Моя группа", result.profileName)
        assertEquals("a", result.profileId)
        assertEquals(listOf("a", "b"), result.config.masterDnsPoolPlan!!.members.map { it.id })
        assertTrue(profiles.all { (it.kernelConfig as KernelConfig.MasterDns).config.localPool == null })
        val individual = resolve(active = null)
        assertNull(individual.config.masterDnsPoolPlan)
        assertNull((individual.config.kernelConfig as KernelConfig.MasterDns).config.localPool)
        assertEquals("DNS a", individual.profileName)
    }

    @Test fun subscriptionRefreshUsesNewAnchorAndMemberCredentials() {
        val before = resolve()
        val updated = listOf("a", "b").map { id ->
            val profile = profile(id)
            profile.copy(kernelConfig = KernelConfig.MasterDns(
                (profile.kernelConfig as KernelConfig.MasterDns).config.copy(key = "refreshed-$id")))
        }
        val after = resolve(updated)
        assertNull(after.error)
        assertEquals(listOf("refreshed-a", "refreshed-b"), after.config.masterDnsPoolPlan!!.members.map { it.config.key })
        assertNotEquals(before.config.masterDnsPoolPlan, after.config.masterDnsPoolPlan)
    }

    @Test fun missingChangedOrForeignMemberAndMissingGroupFailClosed() {
        val failures = listOf(resolve(listOf(profile("a"))), resolve(groups = emptyList()),
            resolve(listOf(profile("a"), profile("b", "foreign"))),
            resolve(listOf(profile("a"), Profile("b", "VPN"))), resolve(selected = "b"),
            resolve(groups = listOf(group, group)))
        failures.forEach {
            assertNotNull(it.error)
            assertNull(it.config.masterDnsPoolPlan)
            assertTrue(runCatching { it.runtimeConfig() }.isFailure)
        }
    }

    @Test fun groupChangesAreReflectedWithoutChangingRealProfiles() {
        val edited = group.copy(name = "Другой DNS", memberIds = listOf("a", "c"), strategy = "round_robin")
        val result = resolve(listOf(profile("a"), profile("b"), profile("c")), listOf(edited))
        assertNull(result.error)
        assertEquals(edited.name, result.profileName)
        assertEquals("round_robin", result.config.masterDnsPoolPlan!!.strategy)
        assertEquals(listOf("a", "c"), result.config.masterDnsPoolPlan.members.map { it.id })
    }

    @Test fun missingSubscriptionIsRejectedAndSubscriptionWithoutRulesUsesAppRouting() {
        val members = listOf(profile("a", "s"), profile("b", "s"))
        val owned = group.copy(subscriptionId = "s")
        assertNotNull(resolve(members, listOf(owned)).error)
        val sub = Subscription("s", "Fixture", "https://example.invalid/sub")
        val snapshot = resolve(members, listOf(owned), subscriptions = listOf(sub))
        assertNull(snapshot.error)
        assertEquals(SharedNetworkSettings(), snapshot.poolRouting)
    }
}
