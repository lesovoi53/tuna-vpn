package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.NetworkDnsSettings
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsPoolSelection
import org.junit.Assert.*
import org.junit.Test

class MasterDnsPoolPlanTest {
    private fun profile(id: String, subscription: String? = "subscription") = Profile(id, "DNS $id",
        KernelConfig.MasterDns(MasterDnsConfig(domains = listOf("$id.example.invalid"),
            key = "synthetic-key-$id", resolvers = "192.0.2.53")), subscriptionId = subscription)
    private fun config(profile: Profile) = (profile.kernelConfig as KernelConfig.MasterDns).config
    private fun pool(anchor: Profile, ids: List<String>, strategy: String = "least_connections") =
        anchor.copy(kernelConfig = KernelConfig.MasterDns(config(anchor).copy(
            localPool = MasterDnsPoolSelection(ids, strategy))))
    private fun routes(profiles: List<Profile>) = profiles.associate { it.id to SharedNetworkSettings() }
    private fun reject(message: String, block: () -> Any?) {
        try {
            block()
            fail("Expected rejection: $message")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(message))
        }
    }

    @Test fun noSelectionKeepsOrdinaryServerPath() {
        assertNull(MasterDnsPoolPlan.resolve(profile("a"), emptyList(), emptyMap()))
        assertNull(MasterDnsPoolPlan.resolve(Profile("a", "VPN"), emptyList(), emptyMap()))
    }

    @Test fun resolvesCurrentMemberConfigsInSelectedOrderAndPreservesLocalOptionsWithoutMutation() {
        val anchor = pool(profile("a"), listOf("b", "a"), "round_robin")
        val other = profile("b").let { it.copy(kernelConfig = KernelConfig.MasterDns(config(it).copy(
            key = "rotated-key", localResolvers = "198.51.100.53", useLocalResolvers = true,
            localPerformanceMode = "stable"))) }
        val profiles = listOf(anchor, other, profile("unselected", "different-subscription"))
        val before = profiles.toList()
        val plan = requireNotNull(MasterDnsPoolPlan.resolve(anchor, profiles, routes(profiles)))
        assertEquals("round_robin", plan.strategy)
        assertEquals(listOf("b", "a"), plan.members.map { it.id })
        assertEquals("rotated-key", plan.members.first().config.key)
        assertEquals(listOf("198.51.100.53"), plan.members.first().config.resolverList())
        assertEquals("192.0.2.53", plan.members.first().config.resolvers)
        assertEquals("stable", plan.members.first().config.localPerformanceMode)
        assertTrue(plan.members.all { it.config.localPool == null })
        assertEquals(before, profiles)
        assertNotNull(config(anchor).localPool)
        assertNotSame(config(other), plan.members.first().config)
    }

    @Test fun missingDeletedDuplicatedAndChangedProtocolMembersNeverFallBack() {
        val anchor = pool(profile("a"), listOf("a", "b"))
        val other = profile("b")
        val profiles = listOf(anchor, other)
        reject("удалён") { MasterDnsPoolPlan.resolve(anchor, listOf(anchor), routes(profiles)) }
        reject("Повторяющийся") {
            MasterDnsPoolPlan.resolve(anchor, profiles + other, routes(profiles))
        }
        reject("больше не использует") {
            MasterDnsPoolPlan.resolve(anchor, listOf(anchor, Profile("b", "Changed")), routes(profiles))
        }
        reject("Некорректные настройки") {
            val broken = other.copy(kernelConfig = KernelConfig.MasterDns(config(other).copy(key = "")))
            MasterDnsPoolPlan.resolve(anchor, listOf(anchor, broken), routes(profiles))
        }
    }

    @Test fun anchorMustBeSelectedAndPresentExactlyOnce() {
        val anchor = pool(profile("a"), listOf("b", "c"))
        val profiles = listOf(anchor, profile("b"), profile("c"))
        reject("Основной сервер") { MasterDnsPoolPlan.resolve(anchor, profiles, routes(profiles)) }
        val valid = pool(profile("a"), listOf("a", "b"))
        reject("удалён") { MasterDnsPoolPlan.resolve(valid, listOf(profile("b")), routes(profiles)) }
        reject("Повторяющийся") {
            MasterDnsPoolPlan.resolve(valid, listOf(valid, valid, profile("b")), routes(profiles))
        }
    }

    @Test fun nestedPoolsAndDifferentSubscriptionsAreRejectedIncludingMixedStandaloneProfiles() {
        val anchor = pool(profile("a"), listOf("a", "b"))
        val nested = pool(profile("b"), listOf("b", "c"))
        reject("Вложенные") { MasterDnsPoolPlan.resolve(anchor, listOf(anchor, nested), routes(listOf(anchor, nested))) }
        for (subscription in listOf(null, "different")) {
            val other = profile("b", subscription)
            reject("одной подписке") {
                MasterDnsPoolPlan.resolve(anchor, listOf(anchor, other), routes(listOf(anchor, other)))
            }
        }
        val standalone = pool(profile("a", null), listOf("a", "b"))
        val members = listOf(standalone, profile("b", null))
        assertEquals(2, MasterDnsPoolPlan.resolve(standalone, members, routes(members))!!.members.size)
    }

    @Test fun missingOrDifferentEffectiveDnsAndRoutingAreRejected() {
        val anchor = pool(profile("a"), listOf("a", "b"))
        val members = listOf(anchor, profile("b"))
        reject("Не определены") { MasterDnsPoolPlan.resolve(anchor, members, emptyMap()) }
        reject("Не определены") { MasterDnsPoolPlan.resolve(anchor, members, routes(listOf(anchor))) }
        val changed = SharedNetworkSettings(dns = NetworkDnsSettings(servers = "198.51.100.53"))
        reject("должны совпадать") {
            MasterDnsPoolPlan.resolve(anchor, members, routes(members) + ("b" to changed))
        }
        val changedRoute = SharedNetworkSettings().let { it.copy(routing = it.routing.copy(bypassLan = true)) }
        reject("должны совпадать") {
            MasterDnsPoolPlan.resolve(anchor, members, routes(members) + ("b" to changedRoute))
        }
    }

    @Test fun plannerEnforcesPoolSizeLimitsBeforeResolvingProfiles() {
        for (size in listOf(1, 9)) {
            val ids = (1..size).map { "p$it" }
            val anchor = pool(profile(ids.first()), ids)
            reject("от 2 до 8") { MasterDnsPoolPlan.resolve(anchor, listOf(anchor), routes(listOf(anchor))) }
        }
        val ids = (1..8).map { "p$it" }
        val anchor = pool(profile(ids.first()), ids)
        val members = listOf(anchor) + ids.drop(1).map { profile(it) }
        assertEquals(8, MasterDnsPoolPlan.resolve(anchor, members, routes(members))!!.members.size)
    }

    @Test fun membershipIsExplicitAndDoesNotInferServersFromNames() {
        // Synthetic grouping fixtures: never combine the FP group with the other group.
        val firstGroup = listOf(profile("fp-a").copy(name = "FP one"), profile("fp-b").copy(name = "FP two"))
        val otherGroup = listOf(profile("other-a").copy(name = "Other one"), profile("other-b").copy(name = "Other two"))
        for (selected in listOf(firstGroup, otherGroup)) {
            val anchor = pool(selected.first(), selected.map { it.id })
            val all = (firstGroup + otherGroup).map { if (it.id == anchor.id) anchor else it }
            val plan = requireNotNull(MasterDnsPoolPlan.resolve(anchor, all, routes(all)))
            assertEquals(selected.map { it.id }, plan.members.map { it.id })
        }
    }

    @Test fun enabledAuxiliaryChainMustHaveSameModeAndSelectedConfiguration() {
        val vless = VlessConfig(
            vlessLink = "vless://00000000-0000-4000-8000-000000000001@proxy.example.invalid:443?security=tls",
            isSocks5Chain = true)
        val anchor = pool(profile("a"), listOf("a", "b")).copy(
            xrayEnabled = true, xrayProtocol = XrayConfiguration.VLESS, vlessConfig = vless)
        val compatible = profile("b").copy(xrayEnabled = true,
            xrayProtocol = XrayConfiguration.VLESS, vlessConfig = vless)
        fun resolve(first: Profile, second: Profile) = listOf(first, second).let {
            MasterDnsPoolPlan.resolve(first, it, routes(it))
        }
        assertEquals(2, resolve(anchor, compatible)!!.members.size)
        val incompatible = listOf(
            compatible.copy(xrayEnabled = false),
            compatible.copy(xrayProtocol = XrayConfiguration.WIREGUARD),
            compatible.copy(vlessConfig = vless.copy(vlessLink = vless.vlessLink.replace("proxy.", "other."))),
            compatible.copy(vlessConfig = vless.copy(isSocks5Chain = false)),
            compatible.copy(vlessConfig = vless.copy(isDualRoute = true, directAddress = "192.0.2.10:443")),
            compatible.copy(vlessConfig = vless.copy(mux = "8")),
        )
        incompatible.forEach { member ->
            reject("дополнительной цепочки") { resolve(anchor, member) }
        }
        val wgAnchor = anchor.copy(xrayProtocol = XrayConfiguration.WIREGUARD,
            wgConfig = WgConfig(privateKey = "synthetic-a", publicKey = "synthetic-public"))
        val wgOther = compatible.copy(xrayProtocol = XrayConfiguration.WIREGUARD, wgConfig = wgAnchor.wgConfig)
        assertEquals(2, resolve(wgAnchor, wgOther)!!.members.size)
        // SOCKS-native Master DNS never passes WG keys to the common router.
        assertEquals(2, resolve(wgAnchor, wgOther.copy(
            wgConfig = wgOther.wgConfig.copy(privateKey = "synthetic-b")))!!.members.size)
    }

    @Test fun disabledAuxiliarySettingsAndUnselectedProtocolConfigDoNotAffectMembership() {
        val anchor = pool(profile("a"), listOf("a", "b"))
        val unused = profile("b").copy(xrayProtocol = XrayConfiguration.VLESS,
            vlessConfig = VlessConfig(vlessLink = "unused", isSocks5Chain = true),
            wgConfig = WgConfig(privateKey = "unused"))
        var members = listOf(anchor, unused)
        assertEquals(2, MasterDnsPoolPlan.resolve(anchor, members, routes(members))!!.members.size)
        val enabledAnchor = anchor.copy(xrayEnabled = true, xrayProtocol = XrayConfiguration.VLESS)
        members = listOf(enabledAnchor, unused.copy(xrayEnabled = true, vlessConfig = enabledAnchor.vlessConfig))
        assertEquals(2, MasterDnsPoolPlan.resolve(enabledAnchor, members, routes(members))!!.members.size)
        // An enabled protocol with no active chain/mux is the same native SOCKS path.
        members = listOf(anchor, unused.copy(xrayEnabled = true,
            vlessConfig = VlessConfig(vlessLink = "unused", directAddress = "unused", hcInterval = "99")))
        assertEquals(2, MasterDnsPoolPlan.resolve(anchor, members, routes(members))!!.members.size)
        members = listOf(anchor, unused.copy(xrayEnabled = true, xrayProtocol = XrayConfiguration.WIREGUARD))
        assertEquals(2, MasterDnsPoolPlan.resolve(anchor, members, routes(members))!!.members.size)
    }
}
