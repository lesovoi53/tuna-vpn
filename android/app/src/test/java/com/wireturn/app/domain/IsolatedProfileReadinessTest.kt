package com.wireturn.app.domain

import com.wireturn.app.data.KernelVariant
import org.junit.Assert.*
import org.junit.Test

class IsolatedProfileReadinessTest {
    @Test fun ordinaryDestinationAndTlsAlertDoNotAbortIsolatedStartup() {
        assertFalse(isIsolatedProfileFatal(KernelVariant.VPN, "INFO outbound/snell: connecting to fatal.example:443"))
        assertFalse(isIsolatedProfileFatal(KernelVariant.VPN, "ERROR transport: remote TLS fatal alert"))
    }
    @Test fun actualNativeFatalAndMasterDnsErrorStillAbortIsolatedStartup() {
        assertTrue(isIsolatedProfileFatal(KernelVariant.VPN, "FATAL[0001] initialize outbound"))
        assertTrue(isIsolatedProfileFatal(KernelVariant.VPN, "panic: runtime failure"))
        assertTrue(isIsolatedProfileFatal(KernelVariant.MASTERDNS, "TUNA_MASTER_ERROR config"))
    }
    @Test fun masterDnsReadinessIsRecognizedAfterResolverSelection() {
        assertFalse(isIsolatedProfileReady(KernelVariant.MASTERDNS, "TUNA_MASTER_CONNECTING"))
        assertFalse(isIsolatedProfileReady(KernelVariant.MASTERDNS, "TUNA_MASTER_ERROR config"))
        assertTrue(isIsolatedProfileReady(KernelVariant.MASTERDNS, "TUNA_MASTER_READY"))
    }
    @Test fun genericSocksLogDoesNotBypassMasterDnsReadiness() {
        assertFalse(isIsolatedProfileReady(KernelVariant.MASTERDNS, "[socks] listening"))
        assertFalse(isIsolatedProfileReady(KernelVariant.VPN, "TUNA_MASTER_READY"))
        assertTrue(isIsolatedProfileReady(KernelVariant.VPN, "sing-box started"))
    }
}
