package com.wireturn.app.domain

import com.wireturn.app.data.ConnectionCategory
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.listCategory
import org.junit.Assert.*
import org.junit.Test

class ProbeEligibilityTest {
    @Test fun vpnSwitchRequiresSameProtocolAndCategory() {
        val source = vless()
        assertTrue(ProbeEligibility.sameSwitchType(source, source.copy(id = "other", subscriptionId = "sub")))
        assertFalse(ProbeEligibility.sameSwitchType(source, vless("Обход БС")))
        for (scheme in listOf("snell", "hy2", "wg", "trojan", "mierus")) {
            val candidate = vless(scheme = scheme)
            assertFalse(scheme, ProbeEligibility.sameSwitchType(source, candidate))
            assertTrue(scheme, ProbeEligibility.sameSwitchType(candidate, candidate.copy(id = "second")))
        }
    }

    @Test fun switchGroupsRejectMixedMissingAndEmptyMembers() {
        val a = vless()
        val b = a.copy(id = "second")
        val snell = vless(scheme = "snell").copy(id = "snell")
        val group = com.wireturn.app.data.ConnectionGroup(id = "g", name = "g",
            category = ConnectionCategory.VPN,
            memberIds = listOf(a.id, b.id), type = com.wireturn.app.data.GroupTestType.URL_TEST)
        val profiles = listOf(a, b, snell)
        assertTrue(ProbeEligibility.sameSwitchGroup(a, group, profiles))
        assertFalse(ProbeEligibility.sameSwitchGroup(a, group.copy(memberIds = listOf(a.id, snell.id)), profiles))
        assertFalse(ProbeEligibility.sameSwitchGroup(a, group.copy(memberIds = listOf("missing")), profiles))
        assertFalse(ProbeEligibility.sameSwitchGroup(a, group.copy(memberIds = emptyList()), profiles))
        assertFalse(ProbeEligibility.sameSwitchGroup(vless("Обход БС"), group, profiles))
    }

    @Test fun bypassSwitchStaysInSameCategoryAndProtocolAcrossSources() {
        val source = Profile("q1", "qWDTT", KernelConfig.Qwdtt())
        assertTrue(ProbeEligibility.sameSwitchType(source, source.copy(id = "q2", subscriptionId = "sub")))
        assertFalse(ProbeEligibility.sameSwitchType(source, Profile("dns", "DNS", KernelConfig.MasterDns())))
        val bypass = vless("Обход БС")
        assertFalse(ProbeEligibility.sameSwitchType(bypass, vless()))
        assertTrue(ProbeEligibility.sameSwitchType(bypass, bypass.copy(id = "second", subscriptionId = "sub")))
        assertFalse(ProbeEligibility.sameSwitchType(bypass, Profile("snell", "Обход БС", KernelConfig.Vpn(VpnConfig("snell://key@example.com:443")))))
        assertFalse(ProbeEligibility.sameSwitchType(vless(), source))
    }

    @Test fun onlyNonVlessBypassRequiresAnActiveSession() {
        assertFalse(ProbeEligibility.requiresActive(vless("Обход БС")))
        assertFalse(ProbeEligibility.requiresActive(vless()))
        for (kernel in listOf(KernelConfig.MasterDns(), KernelConfig.Qwdtt())) {
            assertTrue(ProbeEligibility.requiresActive(Profile("p", "server", kernel)))
        }
    }

    private fun vless(name: String = "Москва", scheme: String = "vless") = Profile("vless", name,
        KernelConfig.Vpn(VpnConfig("$scheme://11111111-2222-4333-8444-555555555555@example.com:443?security=tls")))

    @Test fun ordinaryAndBypassNamedVlessUseTheSameProbeEligibility() {
        val normal = vless()
        val bypass = vless("Москва Обход БС")
        assertEquals(ConnectionCategory.VPN, normal.listCategory)
        assertEquals(ConnectionCategory.BYPASS, bypass.listCategory)
        assertTrue(ProbeEligibility.allows(normal))
        assertTrue(ProbeEligibility.allows(bypass))
        // Eligibility never changes the user's display grouping.
        assertEquals("Москва Обход БС", bypass.name)
        assertEquals(ConnectionCategory.BYPASS, bypass.listCategory)
    }

    @Test fun cfVlessAndUppercaseSchemeAreEligibleInBypassSection() {
        for (name in listOf("[CF] Москва Обход БС", "обход\u00a0бс VLESS", "ОБХОД БС")) {
            val profile = vless(name, "VLESS")
            assertEquals(ConnectionCategory.BYPASS, profile.listCategory)
            assertTrue(ProbeEligibility.allows(profile))
        }
        val websocket = vless("Москва [CF] Обход БС").copy(kernelConfig = KernelConfig.Vpn(VpnConfig(
            "vless://11111111-2222-4333-8444-555555555555@example.com:443?security=tls&type=ws&host=example.com&path=%2F")))
        assertTrue(ProbeEligibility.allows(websocket))
    }

    @Test fun existingVpnEligibilityIsPreservedWithoutBroadeningOtherBypassNamedProtocols() {
        for (scheme in listOf("trojan", "snell", "mierus", "hy2", "ss", "wg", "tt")) {
            val normal = Profile(scheme, "VPN", KernelConfig.Vpn(VpnConfig("$scheme://example.invalid")))
            assertTrue(scheme, ProbeEligibility.allows(normal))
            assertFalse(scheme, ProbeEligibility.allows(normal.copy(name = "Обход БС $scheme")))
        }
        val raw = vless("Обход БС").copy(kernelConfig = KernelConfig.Vpn(VpnConfig(rawXrayJson = "{}")))
        assertFalse(ProbeEligibility.allows(raw))
    }

    @Test fun accountBackedAndOtherBypassCoresRemainIneligibleEvenWithVlessOrCfInTheirName() {
        val kernels = listOf(KernelConfig.MasterDns(), KernelConfig.Turnable(), KernelConfig.Olcrtc(),
            KernelConfig.Webdav(), KernelConfig.FreeTurn(), KernelConfig.Qwdtt())
        kernels.forEachIndexed { index, kernel ->
            val profile = Profile("bypass-$index", "VLESS [CF] VPN", kernel)
            assertEquals(ConnectionCategory.BYPASS, profile.listCategory)
            assertFalse(kernel.javaClass.simpleName, ProbeEligibility.allows(profile))
            assertFalse(ProbeEligibility.allows(profile.copy(name = "Обход БС")))
        }
    }
}
