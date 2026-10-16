package com.wireturn.app.data.kernel

import com.wireturn.app.data.*
import com.wireturn.app.domain.GeoCategoryIndex
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RoutingPolicyTest {
    @Test fun migrationPreservesLegacyOrderAndRoundTrip() {
        val p = RoutingPolicy.load("", "domain:example.com,192.0.2.0/24", "domain:ads.example.com")
        assertEquals(RouteAction.BLOCK, p.rules.first().action)
        assertEquals("VPN", p.fallback)
        assertEquals(p, RoutingPolicy.load(p.toJson(), "ignored", "ignored"))
        assertEquals(3, p.activeRules().size)
    }
    @Test fun modesPreserveRulesWhileChangingDefault() {
        val p = RoutingPolicy(rules = listOf(RoutingRule("domain:example.com", RouteAction.DIRECT), RoutingRule("domain:ads.example", RouteAction.BLOCK)))
        assertEquals("Напрямую", p.copy(mode = RoutingMode.SELECTED_VPN).fallback)
        assertEquals(listOf(p.rules.last()), p.copy(mode = RoutingMode.ALL_VPN).activeRules())
        assertEquals(2, p.rules.size)
        assertTrue(p.copy(rules = p.rules.map { it.copy(enabled = false) }).activeRules().isEmpty())
    }
    @Test fun conflictsUseOrderAndCanBeResolvedByReordering() {
        val p = RoutingPolicy(rules = listOf(RoutingRule("domain:example.com", RouteAction.DIRECT), RoutingRule("domain:sub.example.com", RouteAction.VPN)))
        assertEquals(1, p.warnings().size)
        assertTrue(p.copy(rules = p.rules.reversed()).warnings().isEmpty())
        assertEquals(1, p.copy(rules = listOf(p.rules[0], p.rules[0])).warnings().size)
    }
    @Test fun invalidMatchesAreRejected() {
        listOf("", "https://example.com", "192.0.2.0/33", "a b", "geosite:", "a,b", "999.2.3.4", ":::").forEach { assertFalse(it, RoutingPolicy.validMatch(it)) }
        listOf("domain:example.com", "full:example.com", "192.0.2.0/24", "2001:db8::/32", "geosite:youtube").forEach { assertTrue(it, RoutingPolicy.validMatch(it)) }
    }
    @Test fun categoryIndexReadsNamesAndSkipsUnknownFields() {
        val file = File.createTempFile("geosite", ".dat")
        try {
            file.writeBytes(byteArrayOf(10, 8, 10, 2, 82, 85, 18, 2, 1, 2))
            assertEquals(listOf("geosite:ru"), GeoCategoryIndex.read(file, "geosite:"))
            file.writeBytes(byteArrayOf(10, 100, 10))
            assertTrue(runCatching { GeoCategoryIndex.read(file, "geosite:") }.isFailure)
        } finally { file.delete() }
    }
}
