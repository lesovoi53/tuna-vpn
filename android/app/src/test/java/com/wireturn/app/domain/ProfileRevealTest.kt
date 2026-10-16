package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class ProfileRevealTest {
    private fun profile(id: String, scheme: String = "wireguard", sub: String? = null) =
        Profile(id = id, name = id, kernelConfig = KernelConfig.Vpn(VpnConfig("$scheme://example.invalid:443")), subscriptionId = sub)

    @Test fun waitsForImportedDataThenRevealsLocalVpnAndItsSection() {
        assertNull(ProfileReveal.resolve("new", emptyList(), setOf("sub"), emptySet(), ConnectionCategory.BYPASS, true))
        val imported = profile("new")
        val plan = ProfileReveal.resolve("new", listOf(imported), setOf("sub"), emptySet(), ConnectionCategory.BYPASS, true)!!
        assertEquals(ConnectionCategory.VPN, plan.category)
        assertFalse(plan.subscriptions)
        assertEquals(ProfileSection.WIREGUARD, plan.section)
        assertEquals("section-profile:new", plan.lazyKey)
        assertEquals(listOf("new"), plan.highlightedIds)
        assertNull(plan.expandSubscriptionId)
        assertFalse(plan.manageSubscriptions)
        assertNull(imported.subscriptionId)
    }

    @Test fun favoritesAndSubscriptionChildrenRevealTheirActualRows() {
        val child = profile("child", "awg", "sub")
        val plan = ProfileReveal.resolve("child", listOf(child), setOf("sub"), setOf(ProfileListOrder.key(child)), ConnectionCategory.BYPASS, true)!!
        assertEquals(ProfileSection.FAVORITES, plan.section)
        assertTrue(plan.subscriptions)
        assertEquals("sub", plan.expandSubscriptionId)
        assertEquals(listOf("child", "sub"), plan.highlightedIds)
        val dialog = ProfileReveal.resolve("child", listOf(child), setOf("sub"), emptySet(), ConnectionCategory.VPN, false)!!
        assertEquals("child", dialog.lazyKey)
        assertEquals(ProfileSection.AWG, dialog.section)
    }

    @Test fun subscriptionRequestsKeepHeaderNavigationIncludingEmptySubscriptions() {
        val child = profile("child", sub = "sub")
        val plan = ProfileReveal.resolve("sub", listOf(child), setOf("sub"), emptySet(), ConnectionCategory.BYPASS, true)!!
        assertTrue(plan.subscriptions)
        assertTrue(plan.manageSubscriptions)
        assertEquals("section-sub:sub", plan.lazyKey)
        assertEquals(ConnectionCategory.VPN, plan.category)
        val empty = ProfileReveal.resolve("sub", emptyList(), setOf("sub"), emptySet(), ConnectionCategory.BYPASS, false)!!
        assertEquals("sub_header_sub", empty.lazyKey)
        assertEquals(ConnectionCategory.BYPASS, empty.category)
        assertFalse(empty.manageSubscriptions)
    }

    @Test fun embeddedIndicesCountCollapsedAndEmptySectionsAndSubscriptionManager() {
        val wg = profile("wg")
        val awg = profile("awg", "awg")
        val sections = listOf(ProfileSection.FAVORITES to emptyList(), ProfileSection.WIREGUARD to listOf(wg), ProfileSection.AWG to listOf(awg))
        // 2 controls + 3 pinned rows + 1 manual group = 6 leading rows.
        assertEquals(8, ProfileReveal.embeddedLazyIndex("wg", sections, ProfileSection.WIREGUARD, false, 6, false, false, emptyList()))
        assertEquals(9, ProfileReveal.embeddedLazyIndex("awg", sections, ProfileSection.AWG, false, 6, false, false, emptyList()))
        assertEquals(-1, ProfileReveal.embeddedLazyIndex("wg", sections, null, false, 6, false, false, emptyList()))
        // Search expands the empty favorites row and both protocol rows before the manager.
        assertEquals(14, ProfileReveal.embeddedLazyIndex("sub2", sections, null, true, 6, true, true, listOf("sub1", "sub2")))
        assertEquals(-1, ProfileReveal.embeddedLazyIndex("sub2", sections, null, true, 6, true, false, listOf("sub1", "sub2")))
    }
}
