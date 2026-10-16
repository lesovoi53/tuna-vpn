package com.wireturn.app.data

import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListCategoryTest {
    @Test fun bypassPhraseAnywhereOverridesEveryVpnProtocolForBothProfileSources() {
        for (protocol in listOf("vless", "hysteria2", "snell", "mierus", "trojan", "ss", "tuic", "anytls")) {
            for (name in listOf("москва обход бс", "мартышка обход бс", "это обход бс 3", "ОБХОД БС", "Москва обход\u00a0БС", "обход   бс")) {
                for (subscription in listOf(null, "subscription")) {
                    val p = Profile("id", name, KernelConfig.Vpn(VpnConfig("$protocol://example")), subscriptionId = subscription)
                    assertEquals(ConnectionCategory.BYPASS, p.listCategory)
                    // Naming must not invalidate the protocol-family contract of imported groups.
                    assertEquals(ConnectionCategory.VPN, p.category)
                    assertEquals(GroupFamily.VPN, p.groupFamily)
                }
            }
        }
    }

    @Test fun renamingIsReevaluatedAndOrdinaryProfilesRetainTheirCategory() {
        val vpn = Profile("id", "Москва", KernelConfig.Vpn(VpnConfig()))
        assertEquals(ConnectionCategory.VPN, vpn.listCategory)
        val bypass = vpn.copy(name = "Москва обход бс")
        assertEquals(ConnectionCategory.BYPASS, bypass.listCategory)
        assertEquals(ConnectionCategory.VPN, bypass.copy(name = "Москва").listCategory)
        assertEquals(ConnectionCategory.BYPASS, Profile("qwdtt", "Москва", KernelConfig.Qwdtt()).listCategory)
    }
}
