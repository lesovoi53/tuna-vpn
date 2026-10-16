package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class ProfileSectionsTest {
    private fun vpn(id: String, uri: String = "vless://test@example.invalid:443", name: String = id,
        sub: String? = "sub") = Profile(id = id, name = name, kernelConfig = KernelConfig.Vpn(VpnConfig(uri)), subscriptionId = sub)

    @Test fun supportedProtocolsEachHaveASection() {
        val schemes = listOf("vless", "hysteria2", "snell", "mierus", "trojan", "ss", "tuic", "anytls",
            "naive+https", "wireguard", "awg", "tt")
        val sections = schemes.map { ProfileSections.section(vpn(it, "$it://example.invalid:443")) }
        assertEquals(schemes.size, sections.distinct().size)
        assertFalse(ProfileSection.OTHER in sections)
    }

    @Test fun cfIsASeparateWordAndDoesNotMoveVpnIntoBypass() {
        for (name in listOf("CF Poland", "Poland cf", "Poland (Cf)")) {
            val p = vpn("cf", name = name)
            assertEquals(ProfileSection.VLESS_CF, ProfileSections.section(p))
            assertEquals(ConnectionCategory.VPN, p.listCategory)
        }
        for (name in listOf("xcf", "cf2", "my_cf")) assertEquals(ProfileSection.VLESS, ProfileSections.section(vpn("a", name = name)))
        assertEquals(ProfileSection.CDN, ProfileSections.section(vpn("a", name = "Обход БС CF")))
    }

    @Test fun favoritesMoveExclusivelyAndReturnToTheirProtocol() {
        val a = vpn("a"); val b = vpn("b", "snell://test@example.invalid:443?version=6")
        val original = listOf(a,b)
        val grouped = ProfileSections.groups(original, ConnectionCategory.VPN, true, setOf(ProfileListOrder.key(b)), ProfileSortOrder.ORIGINAL)
        assertEquals(ProfileSection.FAVORITES, grouped.first().first)
        assertEquals(listOf(b), grouped.first().second)
        assertEquals(2, grouped.flatMap { it.second }.size)
        assertFalse(grouped.any { it.first == ProfileSection.SNELL })
        assertEquals(listOf(a,b), original)
        val removed = ProfileSections.groups(original, ConnectionCategory.VPN, true, emptySet(), ProfileSortOrder.ORIGINAL)
        assertEquals(listOf(b), removed.single { it.first == ProfileSection.SNELL }.second)
    }

    @Test fun bypassOrderCoversEverySupportedKernelAndDoesNotDropUnknowns() {
        val rows = listOf(KernelConfig.Webdav(), KernelConfig.MasterDns(), KernelConfig.Qwdtt())
            .mapIndexed { i,k -> Profile(id="$i", name="$i", kernelConfig=k) } + vpn("cdn", name="Обход БС",sub=null)
        val grouped = ProfileSections.groups(rows,ConnectionCategory.BYPASS,false,emptySet(),ProfileSortOrder.ORIGINAL)
        assertEquals(listOf(ProfileSection.FAVORITES,ProfileSection.CDN,ProfileSection.QWDTT,ProfileSection.DNS,ProfileSection.WEBDAV),grouped.map { it.first })
        assertEquals(rows.map { it.id }.toSet(),grouped.flatMap { it.second }.map { it.id }.toSet())
        assertEquals(ProfileSection.OTHER,ProfileSections.section(vpn("future","future://example.invalid:443")))
    }

    @Test fun searchSourcesAndSortKeepFavoritesAndNamesIntact() {
        val a=vpn("a",name="Poland CF");val b=vpn("b",name="Germany CF");val local=vpn("local",name="CF",sub=null)
        val groups=ProfileSections.groups(listOf(a,b,local),ConnectionCategory.VPN,true,emptySet(),ProfileSortOrder.NAME_ASC,"cf")
        assertEquals(listOf(b,a),groups.flatMap { it.second })
        assertEquals(listOf(local),ProfileSections.groups(listOf(a,b,local),ConnectionCategory.VPN,false,emptySet(),ProfileSortOrder.ORIGINAL).flatMap { it.second })
    }

    @Test fun updatedSubscriptionFavoriteRemainsInTopSection() {
        val old=vpn("a").copy(subscriptionSourceId="old")
        val fresh=old.copy(subscriptionSourceId="new", name="Renamed")
        val marks=ProfileListOrder.afterRefresh(listOf(old),listOf(fresh),setOf(ProfileListOrder.key(old)))
        assertEquals(listOf(fresh),ProfileSections.groups(listOf(fresh),ConnectionCategory.VPN,true,marks,ProfileSortOrder.ORIGINAL).first().second)
    }
}
