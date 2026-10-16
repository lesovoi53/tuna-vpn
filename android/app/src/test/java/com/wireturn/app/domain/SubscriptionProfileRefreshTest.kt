package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionProfileRefreshTest {
    @Test fun forbiddenServerFingerprintKeepsServerBaselineAndExplicitLocalFingerprint() {
        val remote = Profile("server", "Remote", KernelConfig.Vpn(VpnConfig(endpoint.replace("firefox", "chrome"))))
        val baseline = KernelConfig.Vpn(VpnConfig(endpoint.replace("firefox", "safari")))
        val local = Profile("local", "Local", KernelConfig.Vpn(VpnConfig(endpoint)),
            subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(baseline))
        val next = SubscriptionProfileRefresh.apply(remote, local, local.id, "sub", remote.id)
        assertEquals(VpnEnginePolicy.apply((local.kernelConfig as KernelConfig.Vpn).config), (next.kernelConfig as KernelConfig.Vpn).config)
        assertEquals(SubscriptionProfileMerge.snapshot(KernelConfig.Vpn(VpnEnginePolicy.apply(baseline.config))), next.subscriptionKernelBaseline)
        assertEquals(next, SubscriptionProfileRefresh.apply(remote, next, next.id, "sub", remote.id))
        val changed = remote.copy(kernelConfig = KernelConfig.Vpn(VpnConfig(endpoint)))
        assertEquals(changed.sanitize().kernelConfig, SubscriptionProfileRefresh.apply(changed, next, next.id, "sub", remote.id).kernelConfig)
    }

    @Test fun snellTfoCorrectionKeepsIdentityAndExplicitLocalNativeSettings() {
        val uri = "snell://fixture-secret@192.0.2.1:19552/?version=5&tfo=true&reuse=true&obfs-mode=http&obfs-host=yandex.ru#Snell"
        val original = Profile("old-uri-id", "Snell", KernelConfig.Vpn(VpnConfig.parse(uri)!!),
            subscriptionUriIdentity = true)
        val local = original.copy(id = "local-snell", subscriptionId = "subscription", subscriptionSourceId = original.id,
            kernelConfig = KernelConfig.Vpn(VpnConfig.parse(uri)!!.copy(options = VpnOptions(
                singBox = com.google.gson.JsonParser.parseString("""{"tcp_fast_open":true}""").asJsonObject))),
            subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(original.kernelConfig))
        val remote = original.copy(id = "new-uri-id", kernelConfig = KernelConfig.Vpn(VpnConfig.parse(uri.replace("tfo=true", "tfo=false"))!!))
        val matched = SubscriptionIdentity.match(remote, listOf(remote), listOf(local), emptySet())
        assertEquals(local, matched)
        val refreshed = SubscriptionProfileRefresh.apply(remote, matched, local.id, "subscription", remote.id)
        assertEquals(local.id, refreshed.id)
        assertEquals("subscription", refreshed.subscriptionId)
        assertEquals(remote.id, refreshed.subscriptionSourceId)
        val config = (refreshed.kernelConfig as KernelConfig.Vpn).config
        assertNotNull(config.options)
        val expected = (original.kernelConfig as KernelConfig.Vpn).config.outbound().apply {
            addProperty("tcp_fast_open", true)
        }
        assertEquals(expected, config.outbound())
        assertEquals(refreshed, SubscriptionProfileRefresh.apply(remote, refreshed, refreshed.id, "subscription", remote.id))
    }

    private val endpoint = "vless://11111111-2222-4333-8444-555555555555@192.0.2.1:9443?security=tls&fp=firefox"
    private fun remote() = Profile("server-id", "Server name", KernelConfig.Vpn(VpnConfig(endpoint)))

    @Test fun successfulRefreshPreservesLocalNameKernelAndChainSettings() {
        val remote = remote()
        val local = remote.copy(id = "local-id", name = "Local rename",
            kernelConfig = KernelConfig.Vpn(VpnConfig(endpoint.replace("firefox", "safari"), engine = "xray")),
            xrayEnabled = true, xrayProtocol = XrayConfiguration.VLESS,
            vlessConfig = VlessConfig(vlessLink = endpoint.replace("192.0.2.1", "192.0.2.2")),
            wgConfig = WgConfig(privateKey = "private-fixture", publicKey = "public-fixture", address = "10.0.0.2/32", mtu = "1420"),
            subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(remote.kernelConfig), subscriptionOriginalName = remote.name)
        val result = SubscriptionProfileRefresh.apply(remote, local, "local-id", "subscription", remote.id)
        assertEquals("local-id", result.id)
        assertEquals("Local rename", result.name)
        assertEquals(local.sanitize().kernelConfig, result.kernelConfig)
        assertTrue(result.xrayEnabled)
        assertEquals(local.xrayProtocol, result.xrayProtocol)
        assertEquals(local.vlessConfig, result.vlessConfig)
        assertEquals(local.wgConfig, result.wgConfig)
        assertEquals("server-id", result.subscriptionSourceId)
        assertEquals("subscription", result.subscriptionId)
        assertEquals(SubscriptionProfileMerge.snapshot(remote.sanitize().kernelConfig), result.subscriptionKernelBaseline)
    }

    @Test fun legacyProfilesWithoutBaselinePreserveExplicitEngineAndNativeOptions() {
        val remote = remote()
        val local = remote.copy(id = "local-id", kernelConfig = KernelConfig.Vpn(VpnConfig(endpoint,
            engine = "xray", options = VpnOptions(xray = com.google.gson.JsonParser.parseString(
                """{"streamSettings":{"sockopt":{"tcpFastOpen":true}}}""").asJsonObject))))
        val result = SubscriptionProfileRefresh.apply(remote, local, local.id, "subscription", remote.id)
        assertEquals(local.sanitize().kernelConfig, result.kernelConfig)
        assertNotNull((result.kernelConfig as KernelConfig.Vpn).config.options)
    }

    @Test fun textUriRotationKeepsMatchedLocalIdAndTakesAllNewUriValues() {
        val first = remote().copy(subscriptionUriIdentity = true)
        val old = first.copy(id = "local-id", subscriptionId = "subscription", subscriptionSourceId = first.id,
            subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(first.kernelConfig))
        val next = first.copy(id = "rotated-uri-id", name = "Rotated server",
            kernelConfig = KernelConfig.Vpn(VpnConfig(endpoint + "&alpn=h2")))
        val matched = SubscriptionIdentity.match(next, listOf(next), listOf(old), emptySet())
        assertEquals(old, matched)
        val result = SubscriptionProfileRefresh.apply(next, matched, matched!!.id, "subscription", next.id)
        assertEquals("local-id", result.id)
        assertEquals("rotated-uri-id", result.subscriptionSourceId)
        assertEquals("Rotated server", result.name)
        assertEquals(next.sanitize().kernelConfig, result.kernelConfig)
    }

    @Test fun webdavRefreshPreservesKnownLocalOverridesAndMigratesLegacyTuning() {
        val remote = Profile("server", "Remote DAV", KernelConfig.Webdav(WebdavConfig(
            webdav = "https://storage.example.test/files", login = "server-user", password = "server-secret", encrypt = true)))
        val localConfig = remote.webdavConfig.copy(login = "local-user", password = "local-secret", encrypt = false,
            dns = "udp://192.0.2.9:53", timeout = "120s", pollMin = "50ms", puts = "16")
        for (baseline in listOf(null, SubscriptionProfileMerge.snapshot(remote.kernelConfig))) {
            val local = remote.copy(id = "local-id", name = "Local DAV", kernelConfig = KernelConfig.Webdav(localConfig),
                subscriptionKernelBaseline = baseline)
            val result = SubscriptionProfileRefresh.apply(remote, local, local.id, "subscription", remote.id)
            assertEquals(if (baseline == null) remote.webdavConfig.copy(dns = localConfig.dns, timeout = localConfig.timeout,
                pollMin = localConfig.pollMin, puts = localConfig.puts) else localConfig, result.webdavConfig)
            assertEquals("Remote DAV", result.name)
            assertEquals("local-id", result.id)
        }
    }




    @Test fun categoryChangeStillRejectsWholeRefresh() {
        val remote = Profile("server", "Bundle", KernelConfig.Webdav(WebdavConfig(webdav = "https://storage.example.test/files", login = "u", password = "p")))
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionProfileRefresh.apply(remote, remote(), "local-id", "subscription", remote.id)
        }
    }
}
