package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class CfProfileUpdateTest {
    private fun original() = Profile("p", "Test CF", KernelConfig.Vpn(VpnConfig(
        "vless://11111111-2222-4333-8444-555555555555@cdn.example:443?type=xhttp&security=tls")), subscriptionId = "s")
    private val ips = listOf("104.16.1.1", "104.16.1.2").map { CfResult(it, listOf(40L, 50L), 2) }
    private fun host(p: Profile) = URI((p.kernelConfig as KernelConfig.Vpn).config.uri).host

    @Test fun switchBetweenMeasuredIpsPreservesCurrentNameAndPreference() {
        val source = original()
        val plan = CloudflareProfile.plan(source)
        val current = plan.profileFor(ips[0].ip).copy(name = "Renamed CF", keepSubscriptionAddress = true)
        val saved = CfSavedAddresses(plan.snapshot, ips, ips[0].ip)
        val selected = CfProfileUpdate.selection(current, saved, ips[1].ip)
        assertEquals(ips[1].ip, host(selected))
        assertEquals(current.copy(kernelConfig = selected.kernelConfig), selected)
        assertTrue(selected.keepSubscriptionAddress)
        val restored = CfProfileUpdate.selection(selected, saved.copy(selectedIp = ips[1].ip), null)
        assertEquals("cdn.example", host(restored))
        assertEquals(source.kernelConfig, restored.kernelConfig)
        assertEquals("Renamed CF", restored.name)
    }

    @Test fun rebasedOriginalRemainsAvailableAfterDisablingAddressLock() {
        val old = original()
        val pinned = CloudflareProfile.plan(old).profileFor(ips[0].ip).copy(keepSubscriptionAddress = true)
        val remote = old.copy(kernelConfig = KernelConfig.Vpn(VpnConfig(
            "vless://aaaaaaaa-2222-4333-8444-555555555555@new.example:8443?type=xhttp&security=tls")))
        val current = SubscriptionProfileRefresh.apply(remote, pinned, old.id, "s", "remote")
        val (unlocked, rebased) = CfProfileUpdate.keepAddress(current, false, CfSavedAddresses(CloudflareProfile.snapshot(old), ips, ips[0].ip))
        assertFalse(unlocked.keepSubscriptionAddress)
        assertEquals(current.kernelConfig, unlocked.kernelConfig)
        assertNotNull(rebased)
        assertTrue(rebased!!.accepts(unlocked))
        assertTrue(rebased.results.isEmpty()) // Old latency must not carry across changed credentials.
        val restored = CfProfileUpdate.selection(unlocked, rebased, null)
        assertEquals("new.example", host(restored))
        assertEquals(8443, URI((restored.kernelConfig as KernelConfig.Vpn).config.uri).port)
    }

    @Test fun unmeasuredAddressCannotBeSelected() {
        val p = original()
        val saved = CfSavedAddresses(CloudflareProfile.snapshot(p), ips)
        assertThrows(IllegalArgumentException::class.java) { CfProfileUpdate.selection(p, saved, "104.16.9.9") }
    }

    @Test fun staleSourceCannotOverwriteChangedProfile() {
        val p = original()
        val saved = CfSavedAddresses(CloudflareProfile.snapshot(p), ips)
        val changed = p.copy(kernelConfig = KernelConfig.Vpn(VpnConfig(
            "trojan://secret@other.example:443?type=ws&security=tls")))
        assertThrows(IllegalArgumentException::class.java) { CfProfileUpdate.selection(changed, saved, ips[0].ip) }
    }
}
