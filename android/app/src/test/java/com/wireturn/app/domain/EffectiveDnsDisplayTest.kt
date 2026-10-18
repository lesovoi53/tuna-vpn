package com.wireturn.app.domain

import com.wireturn.app.R
import com.wireturn.app.data.RouteAction
import com.wireturn.app.data.XraySettings
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class EffectiveDnsDisplayTest {
    @Test fun inheritedGlobalAndProfileSystemBootstrapAreDescribedAsDirect() {
        for (display in listOf(EffectiveDnsDisplay.resolve(XraySettings(bootstrapDns = "system")),
            EffectiveDnsDisplay.resolve(XraySettings(), "system"))) {
            val value = display.rows.single { it.label == R.string.cd_bootstrap }.value
            assertTrue(value, value.contains("Android") && value.contains("DIRECT") && value.contains("вне VPN"))
        }
    }

    @Test fun runtimeEmergencyDescribesDirectExceptionAndNegativeAnswers() {
        val system = EffectiveDnsDisplay.observation(DnsRuntimeTelemetry.parse("TUNA_DNS_STATUS remote 0 emergency-system-negative"), 6)
        assertTrue(system, system.contains("Android") && system.contains("DIRECT") && system.contains("вне VPN"))
        assertTrue(system, system.contains("отрицательный"))
        assertFalse(system, system.contains("DNS 0"))
        val custom = EffectiveDnsDisplay.observation(DnsRuntimeTelemetry.parse("TUNA_DNS_STATUS remote 0 emergency-resolver"), 6)
        assertTrue(custom, custom.contains("через VPN"))
        val direct = EffectiveDnsDisplay.observation(DnsRuntimeTelemetry.parse("TUNA_DNS_STATUS domestic 0 emergency-resolver-negative"), 2)
        assertTrue(direct, direct.contains("DIRECT") && direct.contains("отрицательный"))
        val recovered = EffectiveDnsDisplay.observation(DnsRuntimeTelemetry.parse("TUNA_DNS_STATUS remote 2 selected"), 6)
        assertTrue(recovered, recovered.contains("DNS 2"))
        assertFalse(recovered, recovered.contains("аварийный"))
    }

    @Test fun configuredEmergencyAndBootstrapAreShownWithTheirRoutes() {
        val reserve = SubscriptionPolicyParser.Resolver("TCP", "203.0.113.20:5353", null, RouteAction.DIRECT)
        val split = SplitDnsSettings(remoteEmergency = DnsRecoverySettings("system"),
            domesticEmergency = DnsRecoverySettings("resolver", reserve), bootstrap = DnsRecoverySettings("system"))
        val display = EffectiveDnsDisplay.resolve(XraySettings(splitDnsJson = split.toJson(), bootstrapDns = "tcp://192.0.2.80:53"))
        val remote = display.rows.single { it.label == R.string.cd_remote_dns }.value
        val direct = display.rows.single { it.label == R.string.cd_direct_dns }.value
        val bootstrap = display.rows.single { it.label == R.string.cd_bootstrap }.value
        assertTrue(remote, remote.contains("Аварийный резерв"))
        assertTrue(remote, remote.contains("Android") && remote.contains("напрямую"))
        assertTrue(direct, direct.contains(reserve.endpoint) && direct.contains("напрямую"))
        assertTrue(bootstrap, bootstrap.contains("Android") && bootstrap.contains("напрямую"))
        assertFalse(bootstrap, bootstrap.contains("192.0.2.80"))
        assertTrue(display.rows.all { it.masked(true) })
    }

    @Test fun customRemoteEmergencyStaysVpnAndDisabledEmergencyIsExplicit() {
        val reserve = SubscriptionPolicyParser.Resolver("DOH", "https://reserve.example.invalid/dns-query", "203.0.113.20", RouteAction.VPN)
        val bootstrap = SubscriptionPolicyParser.Resolver("TCP", "203.0.113.21:53", null, RouteAction.DIRECT)
        val split = SplitDnsSettings(remoteEmergency = DnsRecoverySettings("resolver", reserve),
            domesticEmergency = DnsRecoverySettings("disabled"), bootstrap = DnsRecoverySettings("resolver", bootstrap))
        val display = EffectiveDnsDisplay.resolve(XraySettings(splitDnsJson = split.toJson()))
        val remote = display.rows.single { it.label == R.string.cd_remote_dns }.value
        assertTrue(remote, remote.contains(reserve.endpoint) && remote.contains("через VPN"))
        assertTrue(remote, remote.contains("Bootstrap IP: 203.0.113.20"))
        assertTrue(display.rows.single { it.label == R.string.cd_direct_dns }.value.contains("Отключён"))
        assertTrue(display.rows.single { it.label == R.string.cd_bootstrap }.value.contains(bootstrap.endpoint))
    }

    private fun subscriptionSettings(): XraySettings {
        fun servers(count: Int, subnet: String) = (1..count).joinToString(",") {
            """{"transport":"TCP","endpoint":"$subnet.$it:53"}"""
        }
        val document = """{"version":1,"policy":{"RemoteDns":"192.0.2.100","DomesticDns":"192.0.2.101"},"dns":{"remoteServers":[${servers(6, "192.0.2")}],"domesticServers":[${servers(2, "198.51.100")}],"remoteSelectionMode":"priority"}}"""
        val link = "tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(document.toByteArray())
        return XraySettings(dns = "198.51.100.99", splitDnsJson = "", happRoutingSource = TunaRoutingLink.parse(link).source)
    }

    @Test fun connectionDetailsShowAllSubscriptionDnsWhenLocalOverrideIsBlank() {
        val display = EffectiveDnsDisplay.resolve(subscriptionSettings())
        val remote = display.rows.single { it.label == R.string.cd_remote_dns }
        val direct = display.rows.single { it.label == R.string.cd_direct_dns }
        for (i in 1..6) assertTrue("Missing subscription VPN DNS $i: ${remote.value}", remote.value.contains("192.0.2.$i:53"))
        for (i in 1..2) assertTrue("Missing subscription direct DNS $i: ${direct.value}", direct.value.contains("198.51.100.$i:53"))
        assertFalse(remote.value.contains("198.51.100.99"))
        assertNull(direct.valueResource)
        assertEquals("priority", display.split?.remoteSelectionMode)
    }

    @Test fun explicitPoolListsEveryMemberInsteadOfOnlyThePrimary() {
        val settings = subscriptionSettings()
        val dns = SplitDnsSettings.fromPolicy(SubscriptionPolicyParser.parseHapp(settings.happRoutingSource))
        val display = EffectiveDnsDisplay.resolve(settings.copy(happRoutingSource = "", splitDnsJson = dns.toJson()))
        assertTrue(display.rows.single { it.label == R.string.cd_remote_dns }.value.contains("192.0.2.6:53"))
        assertTrue(display.rows.single { it.label == R.string.cd_direct_dns }.value.contains("198.51.100.2:53"))
    }

    @Test fun blankBootstrapDoesNotClaimThatTheSystemResolverWasUsed() {
        val bootstrap = EffectiveDnsDisplay.resolve(XraySettings()).rows.single { it.label == R.string.cd_bootstrap }
        assertNotEquals(R.string.cd_system, bootstrap.valueResource)
        assertTrue(bootstrap.value.isNotBlank())
    }

    @Test fun customOverrideWinsAndDisabledSplitDoesNotInheritTheSubscription() {
        val settings = subscriptionSettings()
        val override = SplitDnsSettings(
            remote = SubscriptionPolicyParser.Resolver("TCP", "203.0.113.10", null, RouteAction.VPN),
            domestic = SubscriptionPolicyParser.Resolver("UDP", "203.0.113.11", null, RouteAction.DIRECT))
        val custom = EffectiveDnsDisplay.resolve(settings.copy(splitDnsJson = override.toJson()))
        assertEquals(override, custom.split)
        assertFalse(custom.rows.any { it.value.contains("192.0.2.1:53") })
        val disabled = EffectiveDnsDisplay.resolve(settings.copy(splitDnsJson = "disabled"))
        assertNull(disabled.split)
        assertEquals(settings.dns, disabled.rows.single { it.label == R.string.cd_remote_dns }.value)
        assertEquals(R.string.cd_not_separate, disabled.rows.single { it.label == R.string.cd_direct_dns }.valueResource)
    }

    @Test fun invalidSourceOrOverrideIsNotSilentlyDisplayedAsCommonDns() {
        val settings = subscriptionSettings()
        for (broken in listOf(settings.copy(splitDnsJson = "invalid"), settings.copy(happRoutingSource = "invalid"))) {
            val display = EffectiveDnsDisplay.resolve(broken)
            assertNotNull(display.notice)
            assertFalse(display.rows.any { it.value.contains(settings.dns) })
        }
    }

    @Test fun rawXrayDoesNotClaimThatCommonOrSubscriptionDnsIsApplied() {
        val display = EffectiveDnsDisplay.resolve(subscriptionSettings(), rawXray = true)
        assertNull(display.split)
        assertNotNull(display.notice)
        assertFalse(display.rows.any { it.value.contains("192.0.") || it.value.contains("198.51.100.99") })
    }

    @Test fun explicitBootstrapAndAllPoolAddressesRemainMaskedInPrivacyMode() {
        val display = EffectiveDnsDisplay.resolve(subscriptionSettings().copy(bootstrapDns = "tcp://192.0.2.80:53"), "tcp://192.0.2.81:53")
        assertEquals("tcp://192.0.2.80:53", display.rows.single { it.label == R.string.cd_bootstrap }.value)
        assertTrue(display.rows.all { it.masked(true) })
        assertTrue(display.rows.none { it.masked(false) })
        val profile = EffectiveDnsDisplay.resolve(XraySettings(), "tcp://192.0.2.81:53")
        assertEquals("tcp://192.0.2.81:53", profile.rows.single { it.label == R.string.cd_bootstrap }.value)
    }
}
