package com.wireturn.app.domain

import com.google.gson.JsonParser
import com.wireturn.app.data.RouteAction
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SplitDnsPoolsTest {
    @Test fun customDohWithoutBootstrapIpSurvivesImportAndBothPools() {
        for (bootstrap in listOf("", ",\"bootstrapIp\":null", ",\"bootstrapIp\":\"\"")) {
            val entry = """{"transport":"DOH","endpoint":"https://andemikus.ru/dns-query"$bootstrap}"""
            val parsed = SplitDnsSettings.parse("""{"remoteServers":[$entry],"domesticServers":[$entry]}""")
            assertEquals("https://andemikus.ru/dns-query", parsed.remote.endpoint)
            assertEquals(RouteAction.VPN, parsed.remote.route)
            assertEquals(RouteAction.DIRECT, parsed.domestic.route)
            assertTrue(parsed.remote.bootstrapIp.isNullOrBlank())
            parsed.copy(hosts = mapOf("andemikus.ru" to "192.0.2.20")).validate()
            assertThrows(IllegalArgumentException::class.java) {
                parsed.copy(remoteServers = listOf(parsed.remote.copy(bootstrapIp = "invalid"))).validate()
            }
        }
        val legacyJson = """{"RemoteDNSType":"DoH","RemoteDNSDomain":"https://andemikus.ru/dns-query","RemoteDNSIP":"","DomesticDns":"77.88.8.8"}"""
        val legacy = SubscriptionPolicyParser.parseHapp("happ://routing/onadd/" + Base64.getEncoder().encodeToString(legacyJson.toByteArray()))
        SplitDnsSettings.fromPolicy(legacy).validate()
        assertNull(legacy.remote.bootstrapIp)
    }
    private fun server(i: Int, route: RouteAction) = SubscriptionPolicyParser.Resolver("UDP", "192.0.2.$i", null, route)
    @Test fun eightIndependentServersAndSelectionSettingsRoundTrip() {
        val dns = SplitDnsSettings(remoteServers = (1..8).map { server(it, RouteAction.VPN) },
            domesticServers = (11..18).map { server(it, RouteAction.DIRECT) }, domesticSelectionMode = "priority", timeoutMs = 3000)
        dns.validate()
        val parsed = SplitDnsSettings.parse(dns.toJson())
        assertEquals(dns.effectiveRemoteServers, parsed.effectiveRemoteServers)
        assertEquals(dns.effectiveDomesticServers, parsed.effectiveDomesticServers)
        assertEquals("auto", parsed.remoteSelectionMode)
        assertEquals("priority", parsed.domesticSelectionMode)
        assertEquals(3000, parsed.timeoutMs)
        assertEquals(parsed.remote, parsed.effectiveRemoteServers.first())
    }
    @Test fun legacySingleSettingsHaveOneResolverPerRouteWithoutReplacement() {
        val old = """{"remote":{"transport":"TCP","endpoint":"192.0.2.53"},"domestic":{"transport":"UDP","endpoint":"192.0.2.54"}}"""
        val parsed = SplitDnsSettings.parse(old)
        assertEquals(listOf(parsed.remote), parsed.effectiveRemoteServers)
        assertEquals("192.0.2.53", parsed.remote.endpoint)
        assertEquals("192.0.2.54", parsed.domestic.endpoint)
        assertEquals(5000, parsed.timeoutMs)
    }
    @Test fun invalidPoolsFailClosed() {
        val r = server(1, RouteAction.VPN)
        for (dns in listOf(SplitDnsSettings(remoteServers = emptyList()),
            SplitDnsSettings(remoteServers = List(9) { server(it + 1, RouteAction.VPN) }),
            SplitDnsSettings(remoteServers = listOf(r, r)),
            SplitDnsSettings(remoteServers = listOf(r.copy(route = RouteAction.DIRECT))),
            SplitDnsSettings(timeoutMs = 10001), SplitDnsSettings(remoteSelectionMode = "race"))) {
            assertThrows(IllegalArgumentException::class.java) { dns.validate() }
        }
    }
    @Test fun tunaRootDnsSurvivesStoredSourceReparse() {
        val root = """{"version":1,"policy":{"RemoteDns":"8.8.8.8","DomesticDns":"77.88.8.8"},"dns":{"remoteServers":[{"transport":"UDP","endpoint":"192.0.2.1"},{"transport":"TCP","endpoint":"192.0.2.2"}],"remoteSelectionMode":"priority","timeoutMs":2500}}"""
        val command = TunaRoutingLink.parse("tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(root.toByteArray()))
        val parsed = SplitDnsSettings.fromPolicy(SubscriptionPolicyParser.parseHapp(command.source))
        assertEquals(2, parsed.effectiveRemoteServers.size)
        assertEquals("77.88.8.8", parsed.domestic.endpoint)
        assertEquals("priority", parsed.remoteSelectionMode)
        assertEquals(2500, parsed.timeoutMs)
        val native = JsonParser.parseString(SplitDnsSettings.applyToPolicy("{}", parsed.toJson())).asJsonObject.getAsJsonObject("splitDns")
        assertEquals(2, native.getAsJsonArray("remoteServers").size())
    }
}
