package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class SubscriptionAddressPolicyTest {
    @Test fun blankTlsNameKeepsNewSubscriptionDomainWhenHostIsPinned() {
        for (suffix in listOf("", "?sni=", "?sni=%20")) {
            val incoming = Profile("remote", "HY2", KernelConfig.Vpn(VpnConfig("hysteria2://secret@new.example:443$suffix")))
            val local = SubscriptionProfileRefresh.apply(incoming, null, "local", "sub", "remote")
                .copy(kernelConfig = KernelConfig.Vpn(VpnConfig("hysteria2://secret@104.16.1.1:443?sni=old.example")), keepSubscriptionAddress = true)
            val updated = SubscriptionProfileRefresh.apply(incoming, local, "local", "sub", "remote")
            val q = CloudflareProfile.query(URI((updated.kernelConfig as KernelConfig.Vpn).config.uri))
            assertEquals("new.example", q["sni"])
        }
    }
    private val link = "vless://11111111-2222-4333-8444-555555555555@origin.example:443/?security=tls&type=xhttp&mode=packet-up&fp=firefox&sni=tls.example&host=http.example&path=%2Fpath%2Bpart&alpn=h2%2Chttp%2F1.1#CF"
    private fun remote(uri: String = link) = Profile("server-id", "CF server", KernelConfig.Vpn(VpnConfig(uri)))
    private fun refresh(remote: Profile, local: Profile?) =
        SubscriptionProfileRefresh.apply(remote, local, local?.id ?: "local-id", "subscription", remote.id)
    private fun uri(profile: Profile) = URI((profile.kernelConfig as KernelConfig.Vpn).config.uri)
    private fun local() = refresh(remote(), null)
    private fun pinned(ip: String = "104.16.1.1") = CloudflareProfile.plan(local()).profileFor(ip).copy(keepSubscriptionAddress = true)

    @Test fun disabledByDefaultAndNeverEnabledByIncomingPayload() {
        val spoofed = remote().copy(keepSubscriptionAddress = true)
        assertFalse(refresh(spoofed, null).keepSubscriptionAddress)
        val edited = pinned().copy(keepSubscriptionAddress = false)
        val next = refresh(spoofed, edited)
        assertFalse(next.keepSubscriptionAddress)
        assertEquals("origin.example", uri(next).host)
    }

    @Test fun lockedHostDoesNotFreezePortCredentialsTlsOrBaseline() {
        val changed = remote(link.replace("origin.example:443", "new.example:8443")
            .replace("11111111", "aaaaaaaa").replace("tls.example", "new-tls.example")
            .replace("http.example", "new-http.example").replace("%2Fpath%2Bpart", "%2Fnew%2Bpath"))
        val next = refresh(changed, pinned())
        assertTrue(next.keepSubscriptionAddress)
        assertEquals("104.16.1.1", uri(next).host)
        assertEquals(8443, uri(next).port)
        assertTrue(uri(next).rawUserInfo.startsWith("aaaaaaaa"))
        val q = CloudflareProfile.query(uri(next))
        assertEquals("new-tls.example", q["sni"])
        assertEquals("new-http.example", q["host"])
        assertEquals("/new+path", q["path"])
        assertEquals("h2,http/1.1", q["alpn"])
        assertEquals(SubscriptionProfileMerge.snapshot(changed.sanitize().kernelConfig), next.subscriptionKernelBaseline)
        assertEquals("local-id", next.id)
        assertEquals("server-id", next.subscriptionSourceId)
        assertEquals(next, refresh(changed, next))
    }

    @Test fun turningOffRestoresLatestSubscriptionAddressOnNextRefresh() {
        val changed = remote(link.replace("origin.example", "new.example"))
        val next = refresh(changed, pinned())
        val restored = refresh(changed, next.copy(keepSubscriptionAddress = false))
        assertEquals("new.example", uri(restored).host)
        assertEquals(changed.sanitize().kernelConfig, restored.kernelConfig)
    }

    @Test fun changedProtocolDoesNotInheritPreviousEndpointOrLock() {
        val changed = remote("snell://fixture@next.example:19552?version=5&obfs-mode=http&obfs-host=obfs.example")
        val next = refresh(changed, pinned())
        assertFalse(next.keepSubscriptionAddress)
        assertEquals("next.example", uri(next).host)
    }

    @Test fun regularVpnProtocolsKeepOnlyHostAndPreserveEncodedRemoteComponents() {
        val fixtures = listOf(
            "snell://p%2Bss%2Fword@remote.example:19552/?version=5&obfs-mode=http&obfs-host=front.example#one%20two",
            "hysteria2://p%2Bss%2Fword@remote.example:443/?sni=tls.example&alpn=h3#one%20two",
            "trojan://p%2Bss%2Fword@remote.example:443/?security=tls&sni=tls.example#one%20two",
            "mierus://user:p%2Bss%2Fword@remote.example?port=443&protocol=TCP&profile=one%20two",
            "naive+https://user:p%2Bss%2Fword@remote.example:443/?sni=tls.example#one%20two"
        )
        for (raw in fixtures) {
            val server = remote(raw)
            val old = refresh(server, null).let {
                it.copy(kernelConfig = KernelConfig.Vpn((it.kernelConfig as KernelConfig.Vpn).config
                    .copy(uri = raw.replace("remote.example", "[2606:4700::1111]"))), keepSubscriptionAddress = true)
            }
            val result = uri(refresh(server, old))
            assertEquals("2606:4700::1111", result.host.removeSurrounding("[", "]"))
            assertEquals(URI(raw).rawUserInfo, result.rawUserInfo)
            assertEquals(URI(raw).rawQuery, result.rawQuery)
            assertEquals(URI(raw).port, result.port)
            assertEquals(URI(raw).rawFragment, result.rawFragment)
        }
    }

    @Test fun implicitCfTlsAndHttpNamesFollowNewSubscriptionNotPinnedIp() {
        val implicit = remote(link.replace("&sni=tls.example&host=http.example", ""))
        val old = CloudflareProfile.plan(refresh(implicit, null)).profileFor("104.16.1.1").copy(keepSubscriptionAddress = true)
        val next = refresh(implicit.copy(kernelConfig = KernelConfig.Vpn(VpnConfig(
            (implicit.kernelConfig as KernelConfig.Vpn).config.uri.replace("origin.example", "new.example")))), old)
        val q = CloudflareProfile.query(uri(next))
        assertEquals("new.example", q["sni"])
        assertEquals("new.example", q["host"])
        assertEquals("104.16.1.1", uri(next).host)
    }

    @Test fun togglingPreferenceDoesNotInvalidateExistingCfResultsOrLegacyProfiles() {
        val plan = CloudflareProfile.plan(local())
        val saved = CfSavedAddresses(plan.snapshot, listOf(CfResult("104.16.1.1", listOf(10, 20), 2)), "104.16.1.1")
        assertTrue(saved.accepts(pinned()))
        assertEquals(saved, saved.forCurrent(pinned()))
        val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
        assertFalse(gson.fromJson(plan.snapshot, Profile::class.java).keepSubscriptionAddress)
        assertEquals(plan.snapshot, CloudflareProfile.snapshot(local().copy(keepSubscriptionAddress = true)))
    }

    @Test fun implicitTlsNameOutsideCfAlsoFollowsTheSubscriptionAddress() {
        val raw = "naive+https://user:fixture@origin.example:443"
        val old = refresh(remote(raw), null).copy(
            kernelConfig = KernelConfig.Vpn(VpnConfig.parse(raw.replace("origin.example", "104.16.1.1"))!!),
            keepSubscriptionAddress = true)
        val next = refresh(remote(raw.replace("origin.example", "new.example")), old)
        assertEquals("104.16.1.1", uri(next).host)
        assertEquals("new.example", CloudflareProfile.query(uri(next))["sni"])
        (next.kernelConfig as KernelConfig.Vpn).config.validate()
    }

    @Test fun repeatedMieruPortsAreKeptVerbatimWhenPreservingHost() {
        val raw = "mierus://user:fixture@origin.example?profile=two%20ports&port=443&protocol=TCP&port=8443&protocol=UDP"
        val original = remote(raw)
        val old = refresh(original, null).copy(kernelConfig = KernelConfig.Vpn(VpnConfig.parse(
            raw.replace("origin.example", "192.0.2.1"))!!), keepSubscriptionAddress = true)
        val next = refresh(original, old)
        assertEquals("192.0.2.1", uri(next).host)
        assertEquals(URI(raw).rawQuery, uri(next).rawQuery)
        (next.kernelConfig as KernelConfig.Vpn).config.validate()
    }

    @Test fun changedSubscriptionKeepsOriginalChoiceButDropsObsoleteMeasurements() {
        val plan = CloudflareProfile.plan(local())
        val saved = CfSavedAddresses(plan.snapshot, listOf(CfResult("104.16.1.1", listOf(10, 20), 2)), "104.16.1.1")
        val next = refresh(remote(link.replace("origin.example", "new.example").replace("tls.example", "new-tls.example")), pinned())
        val rebased = saved.forCurrent(next)!!
        assertTrue(rebased.accepts(next))
        assertEquals("104.16.1.1", rebased.effectiveIp(next))
        assertTrue(rebased.results.isEmpty())
        assertEquals("new.example", uri(CloudflareProfile.plan(CloudflareProfile.restore(rebased.source)).profileFor(null)).host)
        assertNull(saved.forCurrent(next.copy(keepSubscriptionAddress = false)))
        assertEquals(rebased, CfSavedAddresses.fromPinned(next))
    }

    @Test fun originalAddressCanBeSelectedWhileLockIsStillEnabled() {
        val next = refresh(remote(link.replace("origin.example", "new.example")), pinned())
        val record = CfSavedAddresses.fromPinned(next)!!
        val original = CloudflareProfile.restore(record.source).copy(keepSubscriptionAddress = true)
        val refreshed = refresh(remote(link.replace("origin.example", "another.example")), original)
        assertEquals("new.example", uri(refreshed).host)
        assertTrue(refreshed.keepSubscriptionAddress)
    }

    @Test fun textSubscriptionMatchingUsesRemoteBaselineNotLockedAddress() {
        val original = remote().copy(subscriptionUriIdentity = true)
        val old = CloudflareProfile.plan(refresh(original, null)).profileFor("104.16.1.1").copy(keepSubscriptionAddress = true)
        val changed = original.copy(id = "rotated-uri", kernelConfig = KernelConfig.Vpn(VpnConfig(link.replace("fp=firefox", "fp=safari"))))
        val matched = SubscriptionIdentity.match(changed, listOf(changed), listOf(old), emptySet())
        assertEquals(old, matched)
        assertEquals("104.16.1.1", uri(refresh(changed, matched)).host)
    }
}
