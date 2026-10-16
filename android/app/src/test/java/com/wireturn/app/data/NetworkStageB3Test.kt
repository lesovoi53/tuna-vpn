package com.wireturn.app.data

import com.google.gson.JsonParser
import com.wireturn.app.domain.*
import com.wireturn.app.kernel.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NetworkStageB3Test {
    private val listener = SingBoxNetworkCompiler.LocalSocks("127.0.0.1:19080", "fixture-in", "fixture-password")
    private val upstream = SingBoxNetworkCompiler.LocalSocks("127.0.0.1:19081", "fixture-out", "fixture-password")
    private fun settings(dns: SplitDnsSettings, policy: RoutingPolicy = RoutingPolicy()) = SharedNetworkSettings(
        dns = NetworkDnsSettings(splitDnsJson = dns.toJson()), routing = NetworkRoutingSettings(policyJson = policy.toJson()))
    private fun fixture(name: String, value: String) { File("build/network-compiler-fixtures/$name.json").apply { parentFile!!.mkdirs() }.writeText(value) }

    @Test fun dnsOptionsRoundTripAndReachBothCompilers() {
        val dns = SplitDnsSettings(disableCache = true, queryStrategy = "UseIPv4")
        assertEquals(dns, SplitDnsSettings.parse(dns.toJson()))
        val s = settings(dns)
        val xray = XrayNetworkCompiler.compile(s, true).policyJson
        assertTrue(JsonParser.parseString(xray).asJsonObject.getAsJsonObject("splitDns")["disableCache"].asBoolean)
        fixture("xray-policy-dns-controls", xray)
        val extended = SingBoxNetworkCompiler.compile(s, listener, upstream)
        assertEquals("ipv4_only", extended.getAsJsonObject("dns")["strategy"].asString)
        assertTrue(extended.getAsJsonObject("dns")["disable_cache"].asBoolean)
        fixture("extended-dns-controls", extended.toString())
        assertTrue(runCatching { dns.copy(queryStrategy = "bogus").validate() }.isFailure)
        val old = JsonParser.parseString(dns.toJson()).asJsonObject.apply { remove("queryStrategy"); remove("disableCache") }
        assertEquals("UseIP", SplitDnsSettings.parse(old.toString()).queryStrategy)
        assertFalse(SplitDnsSettings.parse(old.toString()).disableCache)
    }

    @Test fun dotKeepsTlsIdentityAndVpnDetourAndCannotSilentlyEnterXray() {
        val dns = SplitDnsSettings(remote = SubscriptionPolicyParser.Resolver("DOT", "tls://dns.example:8853", "192.0.2.53", RouteAction.VPN))
        dns.validateForRouter(NetworkRouter.SING_BOX_EXTENDED)
        val config = SingBoxNetworkCompiler.compile(settings(dns), listener, upstream)
        val remote = config.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject
        assertEquals("tls", remote["type"].asString)
        assertEquals("vpn", remote["detour"].asString)
        assertEquals("192.0.2.53", remote["server"].asString)
        assertEquals(8853, remote["server_port"].asInt)
        assertEquals("dns.example", remote.getAsJsonObject("tls")["server_name"].asString)
        fixture("extended-dot", config.toString())
        assertTrue(runCatching { XrayNetworkCompiler.compile(settings(dns), true) }.isFailure)
        assertTrue(runCatching { dns.copy(remote = dns.remote.copy(bootstrapIp = null)).validate() }.isFailure)
        assertTrue(runCatching { dns.copy(remote = dns.remote.copy(endpoint = "tls://192.0.2.53/path")).validate() }.isFailure)
    }

    @Test fun disablingSniffIsPersistedAndRemovesExtendedAction() {
        val s = settings(SplitDnsSettings(), RoutingPolicy(sniffing = false))
        assertFalse(RoutingPolicy.load(s.routing.policyJson, "", "").sniffing)
        assertFalse(SingBoxNetworkCompiler.compile(s, listener, upstream).getAsJsonObject("route").getAsJsonArray("rules").any {
            it.asJsonObject["action"].asString == "sniff"
        })
        val xray = XrayNetworkCompiler.compile(s, true).policyJson
        assertFalse(JsonParser.parseString(xray).asJsonObject["sniffing"].asBoolean)
        fixture("xray-policy-no-sniff", xray)
    }

    @Test fun explanationHonorsOrderCidrsAndDoesNotGuessRegexOrGeo() {
        val s = settings(SplitDnsSettings(), RoutingPolicy(rules = listOf(
            RoutingRule("full:blocked.example", RouteAction.BLOCK), RoutingRule("domain:example", RouteAction.DIRECT),
            RoutingRule("192.0.2.0/24", RouteAction.BLOCK))))
        val dir = File("build/nonexistent-geodata")
        assertTrue(RouteExplanation.explain(s, "blocked.example", dir).contains("Блокировать"))
        assertTrue(RouteExplanation.explain(s, "www.example", dir).contains("Напрямую"))
        assertTrue(RouteExplanation.explain(s, "192.0.2.44", dir).contains("Блокировать"))
        assertTrue(RouteExplanation.explain(s, "203.0.113.1", dir).contains("Основной маршрут: VPN"))
        val unknown = s.copy(routing = s.routing.copy(policyJson = RoutingPolicy(rules = listOf(RoutingRule("regexp:.*", RouteAction.DIRECT))).toJson()))
        assertTrue(runCatching { RouteExplanation.explain(unknown, "example", dir) }.isFailure)
        assertTrue(runCatching { RouteExplanation.explain(s, "https://example", dir) }.isFailure)
    }
}
