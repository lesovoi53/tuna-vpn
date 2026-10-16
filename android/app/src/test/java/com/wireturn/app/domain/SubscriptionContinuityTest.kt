package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionContinuityTest {
    private val uri = "vless://11111111-2222-4333-8444-555555555555@origin.example:443?security=tls&type=ws&path=%2Fold&fp=firefox"
    private fun profile() = SubscriptionProfileRefresh.apply(Profile("source", "Server", KernelConfig.Vpn(VpnConfig(uri))), null, "local", "sub", "source")

    @Test fun removedServerRetainsIdentityFavoriteAndGroupMembershipThenReattaches() {
        val old = profile()
        val retained = SubscriptionProfileContinuity.afterRefresh(listOf(old), emptyList(), "sub", "Fixture", setOf(old.id)).single()
        assertNull(retained.subscriptionId)
        assertEquals("sub", retained.retainedSubscriptionId)
        assertEquals("Fixture", retained.retainedFromSubscription)
        assertEquals(old.id, retained.id)
        assertEquals(old.kernelConfig, retained.kernelConfig)
        val favorites = ProfileListOrder.afterRefresh(listOf(old), listOf(retained), setOf(ProfileListOrder.key(old)))
        assertTrue(ProfileListOrder.isFavorite(retained, favorites))
        val group = ConnectionGroup(id = "g", name = "G", category = ConnectionCategory.VPN, type = GroupTestType.URL_TEST,
            memberIds = listOf(old.id, "second"), routingProfileId = old.id)
        assertEquals(group, SubscriptionGroupMerge.reconcileLocal(listOf(group), listOf(retained, old.copy(id = "second"))).single())
        val remote = old.copy(id = "source", subscriptionId = null)
        val matched = SubscriptionIdentity.match(remote, listOf(remote), listOf(retained), emptySet())
        assertEquals(retained, matched)
        val restored = SubscriptionProfileRefresh.apply(remote, matched, retained.id, "sub", "source")
        assertNull(restored.retainedFromSubscription)
        assertNull(restored.retainedSubscriptionId)
        val nextFavorites = ProfileListOrder.afterRefresh(listOf(retained), listOf(restored), favorites)
        assertTrue(ProfileListOrder.isFavorite(restored, nextFavorites))
    }

    @Test fun refreshNeverRequestsActivationWhileTunnelOwnsResources() {
        assertFalse(SubscriptionProfileContinuity.mayActivateAfterRefresh("sub", true))
        assertTrue(SubscriptionProfileContinuity.mayActivateAfterRefresh("sub", false))
        assertTrue(SubscriptionProfileContinuity.mayActivateAfterRefresh(null, true))
    }

    @Test fun untouchedUnusedMissingEntryIsRemovedButCustomizedOneIsRetained() {
        val old = profile()
        assertTrue(SubscriptionProfileContinuity.afterRefresh(listOf(old), emptyList(), "sub", "Fixture").isEmpty())
        val customized = old.copy(name = "My local name")
        assertEquals(customized.id, SubscriptionProfileContinuity.afterRefresh(listOf(customized), emptyList(), "sub", "Fixture").single().id)
    }

    @Test fun chainBaselineRefreshesRemoteValuesWithoutOverwritingLocalTuning() {
        val remote = Profile("source", "DAV", KernelConfig.Webdav(WebdavConfig(webdav = "https://example.test/files",
            login = "user", password = "fixture")), xrayEnabled = true,
            wgConfig = WgConfig(privateKey = "private-fixture", publicKey = "public-fixture", address = "10.0.0.2/32", mtu = "1280"))
        val first = SubscriptionProfileRefresh.apply(remote, null, "local", "sub", "source")
        val local = first.copy(wgConfig = first.wgConfig.copy(mtu = "1400"))
        val changed = remote.copy(wgConfig = remote.wgConfig.copy(address = "10.0.0.3/32"))
        val result = SubscriptionProfileRefresh.apply(changed, local, "local", "sub", "source")
        assertEquals("1400", result.wgConfig.mtu)
        assertEquals("10.0.0.3/32", result.wgConfig.address)
        assertEquals(SubscriptionProfileMerge.auxiliarySnapshot(changed.sanitize()), result.subscriptionAuxBaseline)
    }

    @Test fun localTransportOverridesSurviveWhileRemoteCredentialsRotate() {
        val base = profile()
        val local = base.copy(name = "My name", kernelConfig = KernelConfig.Vpn((base.kernelConfig as KernelConfig.Vpn).config.copy(
            uri = uri.replace("fp=firefox", "fp=safari"), engine = "xray")))
        val incomingUri = uri.replace("11111111", "aaaaaaaa").replace("origin.example:443", "new.example:8443").replace("%2Fold", "%2Fnew")
        val incoming = base.copy(name = "Remote rename", kernelConfig = KernelConfig.Vpn(VpnConfig(incomingUri)))
        val actual = SubscriptionProfileRefresh.apply(incoming, local, local.id, "sub", "source")
        val config = (actual.kernelConfig as KernelConfig.Vpn).config
        assertEquals("My name", actual.name)
        assertEquals("Remote rename", actual.subscriptionOriginalName)
        assertTrue(config.uri.contains("aaaaaaaa"))
        assertTrue(config.uri.contains("new.example:8443"))
        assertTrue(config.uri.contains("path=%2Fnew"))
        assertTrue(config.uri.contains("fp=safari"))
        assertEquals("xray", config.selectedEngine)
        assertEquals(SubscriptionProfileMerge.snapshot(incoming.sanitize().kernelConfig), actual.subscriptionKernelBaseline)
        assertEquals(actual, SubscriptionProfileRefresh.apply(incoming, actual, local.id, "sub", "source"))
    }
}
