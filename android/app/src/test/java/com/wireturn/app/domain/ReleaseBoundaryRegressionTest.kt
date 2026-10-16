package com.wireturn.app.domain

import com.wireturn.app.LogLevel
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import org.junit.Assert.*
import org.junit.Test

class ReleaseBoundaryRegressionTest {
    private val uri = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=tls"

    @Test fun emptyEncodedAndBlockedFingerprintsAreNormalizedWithoutChangingTransport() {
        for (fp in listOf("", "%20", "chrome", "Chrome", "random", "randomized")) {
            val input = "$uri&type=xhttp&path=%2Ftest&extra=%7B%22mode%22%3A%22packet-up%22%7D&fp=$fp#test"
            val output = VlessFingerprintImport.uri(input, choose = { "safari" })
            assertEquals(input.replace("fp=$fp#", "fp=safari#"), output)
            assertEquals(output, VlessFingerprintImport.uri(output, choose = { error("Already normalized") }))
        }
    }

    @Test fun explicitOtherFingerprintsAndPlainTransportArePreserved() {
        for (fp in listOf("edge", "safari", "firefox")) {
            val config = VpnConfig("$uri&fp=$fp")
            assertEquals(config, VlessFingerprintImport.forEditor(config, true))
        }
        val plain = uri.replace("security=tls", "security=none") + "&fp="
        assertEquals(plain, VlessFingerprintImport.uri(plain, choose = { error("No TLS") }))
    }

    @Test fun regexUsesRe2DialectAndLinearMatching() {
        for (bad in listOf("[", "\\", "(?=x)x", "(x)\\1", "\\C", "a{1001}"))
            assertFalse(bad, RoutingRegex.isValid(bad))
        for (good in listOf("^example\\.org$", "(?i)example", "(?P<host>example)", "\\p{Greek}+", "\\Q\\C\\E", "\\\\C", "a{1,3}"))
            assertTrue(good, RoutingRegex.isValid(good))
        assertEquals(false, RoutingRegex.matches("(a+)+$", "a".repeat(100_000) + "!"))
        assertEquals(true, RoutingRegex.matches("(?i)example\\.org$", "EXAMPLE.org"))
    }

    @Test fun legacyGroupsCanBeReadButNeverTestBypassMembers() {
        val members = listOf("a", "b").map { Profile(it, "Москва обход БС $it", KernelConfig.Vpn(VpnConfig("$uri&fp=firefox"))) }
        for (type in GroupTestType.entries) {
            val group = ConnectionGroup("group", "Legacy group", ConnectionCategory.VPN, type, members.map { it.id })
            group.validate(members)
            assertTrue(runCatching { group.validateForTesting(members) }.isFailure)
            if (type == GroupTestType.URL_TEST) group.validateForTesting(members.map { it.copy(name = "VPN ${it.id}") })
            else assertTrue(runCatching { group.validateForTesting(members.map { it.copy(name = "VPN ${it.id}") }) }.isFailure)
        }
    }

    @Test fun structuredLevelsAndErrorCountersRemainDistinct() {
        assertEquals(0, LogLevel.severity("[Info] errors=0, failed=0"))
        assertEquals(0, LogLevel.severity("[qWDTT] stats errors=0 failed:0"))
        assertEquals(2, LogLevel.severity("[Error] request failed: fatal=false"))
        assertEquals(3, LogLevel.severity("[Fatal] request failed"))
        assertEquals(1, LogLevel.severity("[Xray] 2026/10/01 [Warning] deprecated setting"))
        assertTrue(LogLevel.ERROR.accepts("[qWDTT] errors=1"))
    }
}
