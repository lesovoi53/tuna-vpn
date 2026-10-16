package com.wireturn.app.data.kernel

import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.domain.SubscriptionPolicyParser
import com.wireturn.app.domain.TunaSubscriptionDocument
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionPolicyTest {
    private val json = """{"Name":"Test routing","GlobalProxy":"true","RouteOrder":"block-proxy-direct",
        "RemoteDNSType":"DoH","RemoteDNSDomain":"https://dns.google/dns-query","RemoteDNSIP":"8.8.8.8",
        "DomesticDNSType":"DoH","DomesticDNSDomain":"https://dns.example.com/dns-query","DomesticDNSIP":"192.0.2.53",
        "BlockSites":["geosite:category-ads"],"DirectSites":["domain:example.com"],"DirectIp":["192.0.2.0/24"],
        "DnsHosts":{"host.example.com":"192.0.2.10"},"DomainStrategy":"IPIfNonMatch","FakeDNS":"false"}"""
    private fun link(s: String = json) = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(s.toByteArray())
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun addIsStoredButOnaddActivates() {
        val add = link().replace("/onadd/", "/add/")
        assertEquals("Test routing", SubscriptionPolicyParser.parseHapp(add).name)
        assertFalse(SubscriptionPolicyParser.activates(add))
        assertTrue(SubscriptionPolicyParser.activates(link()))
        assertFalse(SubscriptionPolicyParser.activates(""))
    }

    @Test fun independentDnsPreservesRulesAndHosts() {
        val settings = com.wireturn.app.domain.SplitDnsSettings()
        val base = """{"mode":"SELECTED_VPN","rules":[{"match":"domain:example.org","action":"VPN"}],"splitDns":{"hosts":{"x.example":"192.0.2.1"}}}"""
        val applied = JsonParser.parseString(com.wireturn.app.domain.SplitDnsSettings.applyToPolicy(base, settings.toJson())).asJsonObject
        assertEquals("SELECTED_VPN", applied["mode"].asString)
        assertEquals(1, applied.getAsJsonArray("rules").size())
        assertEquals("192.0.2.1", applied.getAsJsonObject("splitDns").getAsJsonObject("hosts")["x.example"].asString)
        assertEquals("UDP", applied.getAsJsonObject("splitDns").getAsJsonObject("domestic")["transport"].asString)
        val disabled = JsonParser.parseString(com.wireturn.app.domain.SplitDnsSettings.applyToPolicy(applied.toString(), "disabled")).asJsonObject
        assertFalse(disabled.has("splitDns"))
        assertEquals(1, disabled.getAsJsonArray("rules").size())
    }

    @Test fun independentDnsAcceptsAutomaticDohBootstrapAndRejectsInvalidEndpoints() {
        val defaults = com.wireturn.app.domain.SplitDnsSettings()
        defaults.validate()
        defaults.copy(remote = defaults.remote.copy(endpoint = "https://dns.google/dns-query", bootstrapIp = null)).validate()
        fails { defaults.copy(domestic = defaults.domestic.copy(endpoint = "not-an-ip")).validate() }
        fails { defaults.copy(domestic = defaults.domestic.copy(endpoint = "1.1.1.1:99999")).validate() }
        assertEquals(defaults, com.wireturn.app.domain.SplitDnsSettings.parse(defaults.toJson()))
    }

    @Test fun preservesSplitDnsOrderAndFallback() {
        val p = SubscriptionPolicyParser.fromHeaders(mapOf("rOuTiNg" to listOf(link()), "routing-enable" to listOf("true")))!!
        assertTrue(p.enabled)
        assertEquals(RoutingMode.SELECTED_DIRECT, p.routing.mode)
        assertEquals(listOf(RouteAction.BLOCK, RouteAction.DIRECT, RouteAction.DIRECT), p.routing.rules.map { it.action })
        assertEquals("DOH", p.remote.transport)
        assertEquals(RouteAction.VPN, p.remote.route)
        assertEquals(RouteAction.DIRECT, p.domestic.route)
        assertEquals("192.0.2.53", p.domestic.bootstrapIp)
        assertFalse(p.fakeDns)
        assertEquals("192.0.2.10", p.hosts["host.example.com"])
        assertEquals("IPIfNonMatch", p.domainStrategy)
    }
    @Test fun booleanFalseDoesNotBecomeTrueAndUnknownFieldsSurvive() {
        val s = json.replace("\"GlobalProxy\":\"true\"", "\"GlobalProxy\":false").replace("\"Name\":", "\"futureOption\":7,\"Name\":")
        val p = SubscriptionPolicyParser.parseHapp(link(s))
        assertEquals(RoutingMode.SELECTED_VPN, p.routing.mode)
        assertEquals(7, p.source["futureOption"].asInt)
        assertTrue(p.warnings.any { it.contains("futureOption") })
        assertFalse(p.enabled)
    }
    @Test fun malformedSecuritySensitiveFieldsAreRejected() {
        listOf(
            json.replace("block-proxy-direct", "direct-direct-block"),
            json.replace("\"GlobalProxy\":\"true\"", "\"GlobalProxy\":\"yes\""),
            json.replace("https://dns.google", "http://dns.google"),
            json.replace("8.8.8.8", "dns.google"),
            json.replace("192.0.2.0/24", "domain:example.com"),
            json.replace("IPIfNonMatch", "Invalid")
        ).forEach { bad -> fails { SubscriptionPolicyParser.parseHapp(link(bad)) } }
        fails { SubscriptionPolicyParser.fromHeaders(mapOf("Routing" to listOf(link(), link()))) }
        fails { SubscriptionPolicyParser.parseHapp("happ://routing/onadd/%%%") }
    }
    private fun envelope(): String {
        val root = JsonParser.parseString("""{"schema":"tuna.subscription","schemaVersion":1,"subscriptionId":"test",
            "revision":1,"complete":true,"profiles":[{"id":"a","name":"Test","uri":"vless://test","routingPolicyId":"main"}],
            "routingPolicies":[{"id":"main","format":"happ-routing-v1","enabled":true}],
            "settings":{"schemaVersion":1,"scope":"SUBSCRIPTION","applyMode":"MERGE","values":{"appearance.themeMode":"SYSTEM"},"resetPaths":[]}}""").asJsonObject
        root["routingPolicies"].asJsonArray[0].asJsonObject.addProperty("source", link())
        return root.toString()
    }
    @Test fun envelopePreservesIdsAndReportsUnimplementedSettings() {
        val parsed = TunaSubscriptionDocument.parse(envelope())
        assertEquals("test", parsed.id)
        assertEquals(1L, parsed.revision)
        assertEquals("a", parsed.profiles.single()["id"].asString)
        assertTrue(parsed.unsupportedSettings.isEmpty())
        assertTrue(parsed.routing.containsKey("main"))
    }
    @Test fun incompatibleSnapshotCannotBeApplied() {
        fails { TunaSubscriptionDocument.parse(envelope().replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        fails { TunaSubscriptionDocument.parse(envelope().replace("\"revision\":1", "\"revision\":1.5")) }
        fails { TunaSubscriptionDocument.parse(envelope().replace("\"complete\":true", "\"complete\":false")) }
        fails { TunaSubscriptionDocument.parse(envelope().replace("\"routingPolicyId\":\"main\"", "\"routingPolicyId\":\"absent\"")) }
        fails { TunaSubscriptionDocument.parse(envelope().replace("\"complete\":true", "\"complete\":true,\"requiredCapabilities\":[\"future\"]")) }
        fails { TunaSubscriptionDocument.parse(envelope().replace("\"resetPaths\":[]", "\"resetPaths\":[\"appearance.themeMode\"]")) }
    }
    @Test fun otherOrdersAndPlainDnsAreNotHardcodedToWhitelist() {
        val s = json.replace("block-proxy-direct", "direct-block-proxy")
            .replace("\"GlobalProxy\":\"true\"", "\"GlobalProxy\":false")
            .replace("\"DomesticDNSType\":\"DoH\"", "\"DomesticDNSType\":\"UDP\",\"DomesticDns\":\"192.0.2.53:53\"")
        val p = SubscriptionPolicyParser.parseHapp(link(s))
        assertEquals(RouteAction.DIRECT, p.routing.rules.first().action)
        assertEquals(RouteAction.BLOCK, p.routing.rules.last().action)
        assertEquals(RoutingMode.SELECTED_VPN, p.routing.mode)
        assertEquals("UDP", p.domestic.transport)
        assertEquals("192.0.2.53:53", p.domestic.endpoint)
    }
    @Test fun settingsAreTypedAndCannotAddressArbitraryStorageKeys() {
        val registry = com.wireturn.app.domain.SubscriptionSettingsRegistry
        registry.validate("connection.waitForNetwork", JsonParser.parseString("false"))
        registry.validate("appearance.themeMode", JsonParser.parseString("\"DARK\""))
        fails { registry.validate("connection.waitForNetwork", JsonParser.parseString("\"false\"")) }
        fails { registry.validate("client_socks_auth_enabled_v2", JsonParser.parseString("false")) }
        fails { registry.validate("connection.autoLaunch.intervalMinutes", JsonParser.parseString("1.5")) }
        fails { registry.validate("connection.autoLaunch.checkUrl", JsonParser.parseString("\"http://example.com\"")) }
    }
    @Test fun fragmentsAndPaddingImportWithoutChangingCredentials() {
        assertNotNull(QwdttConfig.parse("qwdtt://config?name=A&peer=example.com%3A56000&hashes=&workers=18&port=9000&pass=demo#Some%20name"))
        val base = "vless://11111111-2222-4333-8444-555555555555@example.com:443?type=xhttp&security=tls&x_padding_bytes=100-1000"
        val p = VpnConfig.parse(base)!!
        assertFalse(p.uri.contains("x_padding_bytes"))
        assertTrue(p.uri.contains("extra="))
        assertNull(VpnConfig.parse(base + "&extra=%7B%22xPaddingBytes%22%3A%22200-300%22%7D"))
    }
    @Test fun groupsUseSubscriptionIdentityAndRejectMixedCategories() {
        val uri = "vless://11111111-2222-4333-8444-555555555555@example.com:443?security=tls"
        val profiles = listOf(
            Profile(id="local-a", name="A", kernelConfig=KernelConfig.Vpn(VpnConfig.parse(uri)!!), subscriptionId="sub", subscriptionSourceId="a"),
            Profile(id="local-b", name="B", kernelConfig=KernelConfig.Vpn(VpnConfig.parse(uri)!!), subscriptionId="sub", subscriptionSourceId="b")
        )
        val doc = JsonParser.parseString("""{"groups":[{"id":"g","name":"Group","category":"VPN","type":"URL_TEST","memberIds":["a","b"],"testUrl":"https://example.com/","probeMethod":"HEAD"}]}""").asJsonObject
        val parsed = com.wireturn.app.domain.SubscriptionGroups.parse(doc,"sub",profiles).single()
        assertEquals(listOf("local-a","local-b"),parsed.memberIds)
        assertEquals(GroupProbeMethod.HEAD,parsed.probeMethod)
        fails { com.wireturn.app.domain.SubscriptionGroups.parse(doc,"other",profiles) }
        fails { com.wireturn.app.domain.SubscriptionGroups.parse(doc,"sub",profiles.map { if(it.id=="local-b") it.copy(kernelConfig=KernelConfig.Qwdtt()) else it }) }
    }
    @Test fun optionalPrivateSubscriptionSample() {
        val path = System.getenv("TUNA_TEST_PROFILES_FILE") ?: return
        val lines = java.io.File(path).readLines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty())
        lines.forEachIndexed { index, line ->
            val valid = when (line.substringBefore(':').lowercase()) {
                "vless", "hy2", "hysteria2", "snell", "mierus" -> VpnConfig.parse(line) != null
                "qwdtt" -> QwdttConfig.parse(line) != null
                "stormdns" -> MasterDnsConfig.parse(line) != null
                else -> false
            }
            assertTrue("Private subscription row ${index + 1} rejected", valid)
        }
    }
}
