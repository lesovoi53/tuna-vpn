package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class ManualGroupMembershipTest {
    private val normal = Profile("a", "Москва", KernelConfig.Vpn(VpnConfig(
        "vless://11111111-2222-4333-8444-555555555555@example.com:443?security=tls")))
    private val bypass = normal.copy(id = "b", name = "москва обход бс 3")
    @Test fun memberPickerRejectsBypassNamesForUrlGroups() {
        for (type in listOf(GroupTestType.URL_TEST)) {
            val group = ConnectionGroup("g", "Test", ConnectionCategory.VPN, type, listOf("a", "b"))
            assertTrue(ManualGroupPolicy.canSelectMember(group, normal))
            assertFalse(ManualGroupPolicy.canSelectMember(group, bypass))
            // Stored/imported data remains readable, but local save and execution reject it.
            group.validate(listOf(normal, bypass))
            assertThrows(IllegalArgumentException::class.java) { group.validateForTesting(listOf(normal, bypass)) }
            val renamed = bypass.copy(name = "Обычный VPN")
            assertTrue(ManualGroupPolicy.canSelectMember(group, renamed))
            group.validateForTesting(listOf(normal, renamed))
        }
    }
}
