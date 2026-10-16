package com.wireturn.app.data

import com.google.gson.JsonParser
import com.wireturn.app.domain.NetworkPolicyResolver
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.domain.SubscriptionPolicyParser
import com.wireturn.app.kernel.FakeDnsOwner
import com.wireturn.app.kernel.SingBoxNetworkCompiler
import com.wireturn.app.kernel.XrayNetworkCompiler
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NetworkCompilerTest {
    private val listener = SingBoxNetworkCompiler.LocalSocks("127.0.0.1:19080", "fixture-in", "fixture-in-password")
    private val upstream = SingBoxNetworkCompiler.LocalSocks("127.0.0.1:19081", "fixture-out", "fixture-out-password")
    private val dns = SplitDnsSettings(
        remote = SubscriptionPolicyParser.Resolver("DOH", "https://dns.example.com/dns-query", "192.0.2.53", RouteAction.VPN),
        domestic = SubscriptionPolicyParser.Resolver("TCP", "192.0.2.54:5353", null, RouteAction.DIRECT),
        hosts = mapOf("host.example" to "192.0.2.10"),
    )
    private fun settings(mode: RoutingMode = RoutingMode.SELECTED_DIRECT) = SharedNetworkSettings(
        dns = NetworkDnsSettings(splitDnsJson = dns.toJson()),
        routing = NetworkRoutingSettings(policyJson = RoutingPolicy(mode, listOf(
            RoutingRule("full:blocked.example", RouteAction.BLOCK),
            RoutingRule("domain:direct.example", RouteAction.DIRECT),
            RoutingRule("keyword:video", RouteAction.VPN),
            RoutingRule("regexp:^vpn[0-9]+\\.example$", RouteAction.VPN),
            RoutingRule("bare.example", RouteAction.VPN),
        )).toJson()),
    )
    private fun compile(s: SharedNetworkSettings = settings()) = SingBoxNetworkCompiler.compile(s, listener, upstream)

    @Test fun automaticDohBootstrapIsSeparateAndRetainsEachPoolRoute() {
        val doh = dns.remote.copy(endpoint = "https://andemikus.ru/dns-query", bootstrapIp = null)
        val split = dns.copy(remoteServers = listOf(doh), domesticServers = listOf(doh.copy(route = RouteAction.DIRECT)))
        for (bootstrap in listOf("", "tcp://192.0.2.80:5353")) {
            val c = compile(settings().copy(dns = NetworkDnsSettings(splitDnsJson = split.toJson(), bootstrap = bootstrap)))
            val servers = c.getAsJsonObject("dns").getAsJsonArray("servers").associate { it.asJsonObject["tag"].asString to it.asJsonObject }
            for ((tag, route) in listOf("dns-vpn" to "vpn", "dns-direct" to "direct")) {
                val member = servers.getValue("$tag-0")
                assertEquals("andemikus.ru", member["server"].asString)
                assertEquals("andemikus.ru", member.getAsJsonObject("tls")["server_name"].asString)
                assertFalse(member.getAsJsonObject("tls").has("insecure"))
                assertEquals("$tag-bootstrap", member["domain_resolver"].asString)
                assertEquals(route, member["detour"].asString)
                val helper = servers.getValue("$tag-bootstrap")
                assertEquals(route, helper["detour"].asString)
                assertEquals(if (bootstrap.isBlank()) "1.1.1.1" else "192.0.2.80", helper["server"].asString)
                assertEquals(listOf("$tag-0"), servers.getValue(tag).getAsJsonArray("servers").map { it.asString })
            }
            if (bootstrap.isBlank()) File("build/network-compiler-fixtures/extended-doh-auto-bootstrap.json").apply { parentFile!!.mkdirs(); writeText(c.toString()) }
        }
        val pinned = split.copy(hosts = mapOf("andemikus.ru" to "192.0.2.44"))
        val c = compile(settings().copy(dns = NetworkDnsSettings(splitDnsJson = pinned.toJson())))
        assertFalse(c.toString().contains("bootstrap"))
        assertTrue(c.toString().contains("192.0.2.44"))
    }

    @Test fun independentDnsPoolsCompileWithoutCrossRouteFallback() {
        val pools = dns.copy(
            remoteServers = listOf(dns.remote, dns.remote.copy(endpoint = "https://192.0.2.55/dns-query", bootstrapIp = "192.0.2.55")),
            domesticServers = listOf(dns.domestic, dns.domestic.copy(endpoint = "192.0.2.56")),
            domesticSelectionMode = "priority", timeoutMs = 3000,
        )
        val c = compile(settings().copy(dns = NetworkDnsSettings(splitDnsJson = pools.toJson())))
        File("build/network-compiler-fixtures").mkdirs()
        File("build/network-compiler-fixtures/extended-dns-pools.json").writeText(c.toString())
        File("build/network-compiler-fixtures/xray-policy-dns-pools.json").writeText(
            XrayNetworkCompiler.compile(settings().copy(dns = NetworkDnsSettings(splitDnsJson = pools.toJson())), true).policyJson)
        val servers = c.getAsJsonObject("dns").getAsJsonArray("servers").associate { it.asJsonObject["tag"].asString to it.asJsonObject }
        for ((tag, route) in listOf("dns-vpn" to "vpn", "dns-direct" to "direct")) {
            val pool = servers.getValue(tag)
            assertEquals("fallback", pool["type"].asString)
            assertEquals("3000ms", pool["timeout"].asString)
            assertEquals(if (route == "vpn") "auto" else "sequential", pool["strategy"].asString)
            val members = pool.getAsJsonArray("servers").map { it.asString }
            assertEquals(listOf("$tag-0", "$tag-1"), members)
            members.forEach { assertEquals(route, servers.getValue(it)["detour"].asString) }
        }
    }

    @Test fun explicitSingletonDnsPoolRetainsConfiguredDeadline() {
        val single=dns.copy(remoteServers=listOf(dns.remote), domesticServers=listOf(dns.domestic),timeoutMs=1000)
        val servers=compile(settings().copy(dns=NetworkDnsSettings(splitDnsJson=single.toJson())))
            .getAsJsonObject("dns").getAsJsonArray("servers").map{it.asJsonObject}
        for(tag in listOf("dns-vpn","dns-direct")) {
            val pool=servers.single{it["tag"].asString==tag}
            assertEquals("fallback",pool["type"].asString)
            assertEquals("1000ms",pool["timeout"].asString)
            assertEquals(1,pool.getAsJsonArray("servers").size())
        }
    }

    @Test fun xrayPolicyRemainsCompatibleWhileOnlyOneLayerOwnsFakeDns() {
        val s = settings()
        val original = NetworkPolicyResolver.resolve(s)
        val result = XrayNetworkCompiler.compile(s, true)
        assertEquals(original.applyOverrides(original.routing.toJson()), result.policyJson)
        assertEquals(FakeDnsOwner.HEV, result.fakeDnsOwner)
        assertEquals(FakeDnsOwner.NONE, XrayNetworkCompiler.compile(s, false).fakeDnsOwner)
        val legacy = SharedNetworkSettings(dns = NetworkDnsSettings(fakeDns = true))
        assertEquals(FakeDnsOwner.XRAY, XrayNetworkCompiler.compile(legacy, false).fakeDnsOwner)
        assertEquals(FakeDnsOwner.HEV, XrayNetworkCompiler.compile(legacy, true).fakeDnsOwner)
        assertTrue(legacy.dns.fakeDns) // The saved user option survives mode changes.
    }

    @Test fun domainOrderActionsAndFallbackArePreserved() {
        val c = compile()
        val allRules = c.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals("sniff", allRules[1].asJsonObject["action"].asString)
        val rules = allRules.filter { it.asJsonObject["action"].asString != "sniff" }
        assertEquals("hijack-dns", rules[0].asJsonObject["action"].asString)
        assertEquals("reject", rules[1].asJsonObject["action"].asString)
        assertEquals("direct.example", rules[2].asJsonObject.getAsJsonArray("domain_suffix")[0].asString)
        assertEquals("direct", rules[2].asJsonObject["outbound"].asString)
        assertEquals("bare.example", rules[5].asJsonObject.getAsJsonArray("domain_keyword")[0].asString)
        assertEquals("vpn", c.getAsJsonObject("route")["final"].asString)
        assertEquals("direct", compile(settings(RoutingMode.SELECTED_VPN)).getAsJsonObject("route")["final"].asString)
        val allVpn = compile(settings(RoutingMode.ALL_VPN))
        assertEquals(3, allVpn.getAsJsonObject("route").getAsJsonArray("rules").size())
        assertEquals("vpn", allVpn.getAsJsonObject("route")["final"].asString)
    }

    @Test fun remoteDnsNeverFallsBackToDirectAndDohRetainsTlsName() {
        val c = compile()
        val d = c.getAsJsonObject("dns")
        val servers = d.getAsJsonArray("servers")
        val remote = servers[0].asJsonObject
        assertEquals("vpn", remote["detour"].asString)
        assertEquals("192.0.2.53", remote["server"].asString)
        assertEquals("dns.example.com", remote.getAsJsonObject("tls")["server_name"].asString)
        assertFalse(remote.getAsJsonObject("tls").has("insecure"))
        assertEquals("direct", servers[1].asJsonObject["detour"].asString)
        assertEquals(5353, servers[1].asJsonObject["server_port"].asInt)
        assertEquals("static-hosts", d.getAsJsonArray("rules")[0].asJsonObject["server"].asString)
        assertEquals("reject", d.getAsJsonArray("rules")[1].asJsonObject["action"].asString)
        assertEquals("dns-vpn", d["final"].asString)
        assertFalse(c.toString().contains("1.1.1.1"))
        assertFalse(c.toString().contains("8.8.8.8"))
    }

    @Test fun authIsRequiredOnBothLocalHopsAndSelfLoopsAreRejected() {
        assertTrue(runCatching { SingBoxNetworkCompiler.compile(settings(), listener, listener) }.isFailure)
        for (bad in listOf(upstream.copy(username = ""), upstream.copy(password = ""), upstream.copy(address = "0.0.0.0:19081"))) {
            assertTrue(runCatching { SingBoxNetworkCompiler.compile(settings(), listener, bad) }.isFailure)
        }
        val c = compile()
        assertEquals("fixture-in", c.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonArray("users")[0].asJsonObject["username"].asString)
        assertEquals("fixture-out", c.getAsJsonArray("outbounds")[0].asJsonObject["username"].asString)
        assertEquals(2, c.getAsJsonArray("outbounds").size()) // No selector with a DIRECT fallback.
    }

    @Test fun unsupportedPoliciesFailBeforeAConfigCanBeLaunched() {
        for (rule in listOf("geosite:youtube", "geoip:private")) {
            val s = settings().copy(routing = NetworkRoutingSettings(policyJson = RoutingPolicy(
                rules = listOf(RoutingRule(rule, RouteAction.DIRECT))).toJson()))
            assertTrue(rule, runCatching { compile(s) }.isFailure)
        }
        assertTrue(runCatching { compile(SharedNetworkSettings()) }.isFailure)
        assertTrue(runCatching { compile(settings().copy(dns = NetworkDnsSettings(splitDnsJson = "disabled"))) }.isFailure)
        assertTrue(runCatching { SingBoxNetworkCompiler.compile(settings().copy(dns = settings().dns.copy(fakeDns = true)), listener, upstream, hevEnabled = false) }.isFailure)
        assertTrue(compile(settings().copy(routing = settings().routing.copy(bypassLan = true))).has("route"))
    }

    @Test fun fakeDnsIsNotEnabledWhenSplitDnsOverrideDisablesIt() {
        val s = SharedNetworkSettings(dns = NetworkDnsSettings(fakeDns = true, splitDnsJson = "disabled"))
        assertEquals(FakeDnsOwner.NONE, XrayNetworkCompiler.compile(s, false).fakeDnsOwner)
    }

    @Test fun invalidImportedEnumsCannotTurnIntoAVpnFallback() {
        for (json in listOf(
            """{"mode":"UNKNOWN","rules":[]}""",
            """{"mode":"SELECTED_DIRECT","rules":[{"match":"domain:example.com","action":"UNKNOWN","enabled":true}]}""",
        )) {
            assertTrue(runCatching { compile(settings().copy(routing = NetworkRoutingSettings(policyJson = json))) }.isFailure)
        }
    }

    @Test fun syntheticFixturesForNativeCheckCoverModesAndDnsTransports() {
        val dir = File("build/network-compiler-fixtures").apply { mkdirs() }
        for (mode in RoutingMode.entries) {
            File(dir, "extended-${mode.name}.json").writeText(compile(settings(mode)).toString())
            File(dir, "xray-policy-${mode.name}.json").writeText(XrayNetworkCompiler.compile(settings(mode), true).policyJson)
        }
        val udp = dns.copy(remote = SubscriptionPolicyParser.Resolver("UDP", "[2001:db8::53]:5353", null, RouteAction.VPN))
        val c = compile(settings().copy(dns = NetworkDnsSettings(splitDnsJson = udp.toJson())))
        assertEquals("udp", c.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject["type"].asString)
        File(dir, "extended-udp-ipv6.json").writeText(c.toString())
        assertTrue(JsonParser.parseString(File(dir, "extended-ALL_VPN.json").readText()).isJsonObject)
    }
}
