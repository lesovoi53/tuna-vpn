package com.wireturn.app.domain

import com.wireturn.app.data.Profile
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class ProfileListOrderTest {
    private fun p(id: String, name: String, protocol: String = "vless") =
        Profile(id, name, KernelConfig.Vpn(VpnConfig("$protocol://example.invalid")))
    @Test fun favoritesStayFirstForEverySortModeWithoutChangingTheInput() {
        val a = p("a", "A"); val b = p("b", "B"); val favorite = p("f", "ZZZ", "trojan")
        val source = listOf(b, a, favorite); val marked = setOf(ProfileListOrder.key(favorite))
        ProfileSortOrder.entries.forEach { order ->
            assertEquals("f", ProfileListOrder.sorted(source, order, marked).first().id)
        }
        assertEquals(listOf("b", "a", "f"), source.map { it.id })
    }
    @Test fun originalOrderIsStableWithinFavoriteAndRegularPartitions() {
        val source = (1..5).map { p("$it", "$it") }
        val result = ProfileListOrder.sorted(source, ProfileSortOrder.ORIGINAL, setOf(ProfileListOrder.key(source[3]), ProfileListOrder.key(source[1])))
        assertEquals(listOf("2", "4", "1", "3", "5"), result.map { it.id })
    }
    @Test fun namesAndProtocolsHaveDeterministicOrdering() {
        val source = listOf(p("a", "Zulu", "vless"), p("b", "alpha", "trojan"), p("c", "Beta", "trojan"))
        assertEquals(listOf("b", "c", "a"), ProfileListOrder.sorted(source, ProfileSortOrder.NAME_ASC, emptySet()).map { it.id })
        assertEquals(listOf("a", "c", "b"), ProfileListOrder.sorted(source, ProfileSortOrder.NAME_DESC, emptySet()).map { it.id })
        assertEquals(listOf("b", "c", "a"), ProfileListOrder.sorted(source, ProfileSortOrder.PROTOCOL, emptySet()).map { it.id })
    }
    @Test fun subscriptionRenameCredentialAndLocalIdChangeRetainFavorite() {
        val original = p("local-a", "Old name").copy(subscriptionId = "sub", subscriptionSourceId = "server-id")
        val fresh = p("local-b", "New name", "trojan").copy(subscriptionId = "sub", subscriptionSourceId = "server-id")
        assertTrue(ProfileListOrder.isFavorite(fresh, setOf(ProfileListOrder.key(original))))
    }
    @Test fun sameNamedProfilesAndOtherSubscriptionsDoNotShareFavorites() {
        val original = p("a", "Same").copy(subscriptionId = "first", subscriptionSourceId = "server")
        val other = original.copy(id = "b", subscriptionId = "second")
        assertNotEquals(ProfileListOrder.key(original), ProfileListOrder.key(other))
        assertNotEquals(ProfileListOrder.key(p("a", "Same")), ProfileListOrder.key(p("b", "Same")))
    }
    @Test fun identityFieldsCannotCollideAtSeparators() {
        val a = p("a", "A").copy(subscriptionId = "a:1", subscriptionSourceId = "b")
        val b = p("b", "B").copy(subscriptionId = "a", subscriptionSourceId = "1:b")
        assertNotEquals(ProfileListOrder.key(a), ProfileListOrder.key(b))
    }
    @Test fun delayedToggleUsesTheRefreshedKeyAndDoesNotResurrectDeletedProfiles() {
        val old = p("local", "Old").copy(subscriptionId = "sub", subscriptionSourceId = "old-hash")
        val fresh = old.copy(subscriptionSourceId = "new-hash")
        val marked = setOf(ProfileListOrder.key(fresh), "profile:unrelated")
        assertEquals(setOf("profile:unrelated"), ProfileListOrder.toggled(fresh, old, marked))
        assertEquals(marked, ProfileListOrder.toggled(null, old, marked))
        assertEquals(marked, ProfileListOrder.toggled(fresh.copy(subscriptionId = "other"), old, marked))
    }
    @Test fun refreshDoesNotTransferFavoritesWithoutUnambiguousLocalContinuity() {
        val old = p("local", "Same").copy(subscriptionId = "sub", subscriptionSourceId = "old")
        val marked = setOf(ProfileListOrder.key(old))
        for (next in listOf(listOf(old.copy(id = "new", subscriptionSourceId = "new")),
                listOf(old.copy(subscriptionId = "other", subscriptionSourceId = "new")),
                listOf(old.copy(subscriptionSourceId = "a"), old.copy(subscriptionSourceId = "b")))) {
            assertEquals(marked, ProfileListOrder.afterRefresh(listOf(old), next, marked))
        }
        val fresh = old.copy(subscriptionSourceId = "new")
        assertEquals(setOf(ProfileListOrder.key(fresh)), ProfileListOrder.afterRefresh(listOf(old), listOf(fresh), marked))
    }
}
