package com.wireturn.app.domain

import com.wireturn.app.data.ConnectionCategory
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class PinnedXrayBalancerSectionsTest {
    private val balancedJson = """{"outbounds":[{"tag":"proxy1","protocol":"freedom"},{"tag":"proxy2","protocol":"freedom"}],"routing":{"balancers":[{"tag":"auto","selector":["proxy"]}],"rules":[{"type":"field","network":"tcp,udp","balancerTag":"auto"}]}}"""
    private val directJson = """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
    private fun raw(id: String, subscription: String? = null, name: String = id, balanced: Boolean = true) =
        Profile(id = id, name = name, subscriptionId = subscription,
            kernelConfig = KernelConfig.Vpn(VpnConfig(rawXrayJson = if (balanced) balancedJson else directJson)))
    private fun vpn(id: String, subscription: String? = null) = Profile(id = id, name = id, subscriptionId = subscription,
        kernelConfig = KernelConfig.Vpn(VpnConfig("vless://test@example.invalid:443")))

    @Test fun globalPinnedOrderIgnoresCategorySourceAndFavorites() {
        val remote = raw("remote", "subscription")
        val localBypassName = raw("local", name = "Обход БС balanced")
        val normal = vpn("normal")
        val original = listOf(normal, remote, localBypassName)
        assertEquals(listOf(remote, localBypassName), ProfileSections.pinnedBalancers(original))
        for (category in ConnectionCategory.entries) for (subscriptions in listOf(false, true)) {
            val ordinary = ProfileSections.groups(original, category, subscriptions,
                setOf(ProfileListOrder.key(remote)), ProfileSortOrder.NAME_DESC, "no-match").flatMap { it.second }
            assertTrue(ordinary.none(ProfileSections::isPinnedBalancer))
            assertEquals(listOf(remote, localBypassName), ProfileSections.pinnedBalancers(original))
        }
        assertEquals(listOf(normal, remote, localBypassName), original)
    }

    @Test fun balancerDoesNotAppearTwiceInFavoriteOrProtocolSections() {
        val pinned = raw("balancer", "subscription")
        val normal = vpn("normal", "subscription")
        val profiles = listOf(pinned, normal)
        val ordinary = ProfileSections.groups(profiles, ConnectionCategory.VPN, true,
            setOf(ProfileListOrder.key(pinned)), ProfileSortOrder.ORIGINAL).flatMap { it.second }
        val rendered = ProfileSections.pinnedBalancers(profiles) + ordinary
        assertEquals(listOf(pinned, normal), rendered)
        assertEquals(rendered.size, rendered.map { it.id }.distinct().size)
    }

    @Test fun pinnedIndicesAccountForHeaderAndTitleAndResolveSelectedId() {
        val a = raw("a"); val b = raw("b", "sub"); val plain = vpn("plain")
        assertEquals(0, ProfileSections.pinnedBalancerItemCount(listOf(plain)))
        assertEquals(2, ProfileSections.pinnedBalancerItemCount(listOf(plain, a)))
        assertEquals(3, ProfileSections.pinnedBalancerItemCount(listOf(plain, a, b)))
        assertEquals(2, ProfileSections.pinnedBalancerLazyIndex(listOf(plain, a, b), a.id))
        assertEquals(3, ProfileSections.pinnedBalancerLazyIndex(listOf(plain, a, b), b.id))
        assertEquals(-1, ProfileSections.pinnedBalancerLazyIndex(listOf(plain, a, b), plain.id))
    }

    @Test fun duplicateIdsDoNotCreateDuplicatePinnedLazyKeys() {
        val a = raw("a")
        assertEquals(listOf(a), ProfileSections.pinnedBalancers(listOf(a, a)))
        assertEquals(2, ProfileSections.pinnedBalancerItemCount(listOf(a, a)))
    }

    @Test fun rawWithoutBalancerUsesOrdinaryJsonSectionWhileVlessIsUnchanged() {
        val json = raw("json", balanced = false)
        val plain = vpn("vless")
        assertEquals(ProfileSection.XRAY_JSON, ProfileSections.section(json))
        assertEquals(ProfileSection.VLESS, ProfileSections.section(plain))
        assertTrue(ProfileSections.pinnedBalancers(listOf(json, plain)).isEmpty())
        assertEquals(2, ProfileSections.groups(listOf(json, plain), ConnectionCategory.VPN, false,
            emptySet(), ProfileSortOrder.ORIGINAL).flatMap { it.second }.size)
    }
}
