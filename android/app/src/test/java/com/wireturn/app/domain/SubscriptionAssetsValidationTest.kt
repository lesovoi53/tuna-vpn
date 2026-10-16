package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.Base64

class SubscriptionAssetsValidationTest {
    @Test fun onlyEffectiveEnabledCategoriesAreRequiredAndOwnCategoriesAreValidated() {
        val dir = Files.createTempDirectory("routing-local-assets-test").toFile()
        try {
            val source = source()
            val policy = SubscriptionPolicyParser.parseHapp(source).routing
            val disabled = policy.rules.fold(SubscriptionRuleOverrides()) { overrides, rule -> overrides.toggle(rule, false) }
            val noGeo = disabled.apply(policy)
            SubscriptionRoutingAssets.validateDirectory(dir, source, noGeo)
            val own = disabled.copy(userRules = listOf(com.wireturn.app.data.RoutingRule("geosite:own", com.wireturn.app.data.RouteAction.VPN)))
            assertTrue(runCatching { SubscriptionRoutingAssets.validateDirectory(dir, source, own.apply(policy)) }.isFailure)
            File(dir, "geosite.dat").writeBytes(database("OWN"))
            SubscriptionRoutingAssets.validateDirectory(dir, source, own.apply(policy))
            val compiled = com.wireturn.app.data.RoutingPolicy.load(SubscriptionRoutingAssets.nativePolicy(source, own.apply(policy)), "", "")
            assertEquals(own.userRules, compiled.activeRules())
        } finally { dir.deleteRecursively() }
    }
    private fun source() = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(
        """{"Name":"Test","RemoteDNSType":"TCP","RemoteDns":"192.0.2.53","DomesticDNSType":"UDP","DomesticDns":"192.0.2.54","BlockSites":["geosite:demo"],"DirectIp":["geoip:private"]}""".toByteArray())
    private fun database(category: String): ByteArray {
        val bytes = category.toByteArray()
        return byteArrayOf(10, (bytes.size + 2).toByte(), 10, bytes.size.toByte()) + bytes
    }
    @Test fun missingWrongAndCorruptCategoriesAreRejectedBeforePublication() {
        val dir = Files.createTempDirectory("routing-assets-test").toFile()
        try {
            val policy = source()
            assertTrue(runCatching { SubscriptionRoutingAssets.validateDirectory(dir, policy) }.isFailure)
            File(dir, "geosite.dat").writeBytes(database("DEMO"))
            File(dir, "geoip.dat").writeBytes(database("OTHER"))
            assertTrue(runCatching { SubscriptionRoutingAssets.validateDirectory(dir, policy) }.isFailure)
            File(dir, "geoip.dat").writeBytes(database("PRIVATE"))
            SubscriptionRoutingAssets.validateDirectory(dir, policy)
            File(dir, "geosite.dat").writeText("<html>download error</html>")
            assertTrue(runCatching { SubscriptionRoutingAssets.validateDirectory(dir, policy) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
}
