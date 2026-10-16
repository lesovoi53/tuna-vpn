package com.wireturn.app.domain

import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.VpnConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WgProbeGuardTest {
    private fun config(key: Int = 1, mtu: Int = 1280, dns: String = "1.1.1.1", name: String = "original"): KernelConfig {
        fun k(value: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { value.toByte() })
        return KernelConfig.Vpn(VpnConfig("wg://192.0.2.1:51820?private_key=${k(key)}&public_key=${k(2)}&local_address=10.0.0.2%2F32&mtu=$mtu&dns=$dns#$name"))
    }
    @Test fun activeAccountUsesExistingProxyAndNeverCreatesAnotherPeer() = runBlocking {
        val active = ClientConfig(kernelConfig = config(), socksAddr = "127.0.0.1:1080")
        var starts = 0
        val answer = WgProbeGuard.run(config(name = "renamed"), active, true,
            isolated = { starts++; "wrong" }, reuse = { assertSame(active, it); "existing" })
        assertEquals("existing", answer); assertEquals(0, starts)
    }
    @Test fun differentSettingsAndStartingSessionDoNotStealTheActiveAccount() = runBlocking {
        val active = ClientConfig(kernelConfig = config())
        for ((requested, ready) in listOf(config(mtu = 1400) to true, config(dns = "9.9.9.9") to true, config() to false)) {
            var starts = 0
            val failure = runCatching { WgProbeGuard.run(requested, active, ready, { starts++ }, { error("Wrong settings reused") }) }.exceptionOrNull()
            assertTrue(failure is ActiveWgProbeConflict); assertEquals(0, starts)
        }
    }
    @Test fun otherAccountsAndNoActiveSessionStillUseIsolatedMeasurement() = runBlocking {
        val active = ClientConfig(kernelConfig = config())
        assertEquals(7, WgProbeGuard.run(config(key = 3), active, true, { 7 }, { 0 }))
        assertEquals(8, WgProbeGuard.run(config(), null, false, { 8 }, { 0 }))
        val vless = KernelConfig.Vpn(VpnConfig("vless://test@192.0.2.2:443?security=tls"))
        assertEquals(9, WgProbeGuard.run(vless, active, true, { 9 }, { 0 }))
    }
    @Test fun cancellingReusedProbeDoesNotFallBackToAnotherInstance() = runBlocking {
        var starts = 0
        val cancellation = kotlinx.coroutines.CancellationException("test cancelled")
        val result = runCatching { WgProbeGuard.run(config(), ClientConfig(kernelConfig = config()), true,
            { starts++ }, { throw cancellation }) }
        assertSame(cancellation, result.exceptionOrNull()); assertEquals(0, starts)
    }
}
