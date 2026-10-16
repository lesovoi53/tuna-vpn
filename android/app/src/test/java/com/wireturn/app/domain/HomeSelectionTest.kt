package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.WebdavConfig
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class HomeSelectionTest {
    private val old = ClientConfig(kernelConfig = KernelConfig.Webdav(WebdavConfig()))
    private val naive = ClientConfig(kernelConfig = KernelConfig.Vpn(VpnConfig("naive+https://user:pass@example.com:443")))
    private val group = ConnectionGroup("g", "Naive group", ConnectionCategory.VPN, GroupTestType.URL_TEST, listOf("naive"))

    @Test fun stoppedGroupDoesNotInheritPreviousVolgaConfigOrServer() {
        assertNull(HomeSelection.config(old, old, false, group))
        assertNull(HomeSelection.member(group, Profile("old", "Old Volga", old.kernelConfig), false))
    }
    @Test fun stoppedGroupDoesNotClaimItsPreviouslyUsedMemberIsActive() {
        assertNull(HomeSelection.member(group, Profile("naive", "Naive", naive.kernelConfig), false))
    }
    @Test fun activeGroupOnlyShowsItsOwnMember() {
        val member = Profile("naive", "Naive", naive.kernelConfig)
        assertEquals(member, HomeSelection.member(group, member, true))
        assertNull(HomeSelection.member(group, Profile("old", "Old Volga", old.kernelConfig), true))
        assertEquals(naive, HomeSelection.config(old, naive, true, group))
    }
    @Test fun stoppedIndividualUsesSelectedConfigNotStaleSession() {
        assertEquals(naive, HomeSelection.config(naive, old, false, null))
    }
    @Test fun runningIndividualKeepsActualRuntimeConfig() {
        assertEquals(old, HomeSelection.config(naive, old, true, null))
    }
}
