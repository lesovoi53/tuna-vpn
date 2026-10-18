package com.wireturn.app.domain

import com.google.gson.JsonParser
import com.wireturn.app.data.Profile
import com.wireturn.app.data.RouteAction
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.Subscription
import com.wireturn.app.kernel.XrayNetworkCompiler
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDnsCompilerTest {
    private fun compile(dns: SplitDnsSettings? = null): com.google.gson.JsonObject {
        val document = JsonParser.parseString("""{"version":1,"policy":{"RemoteDNSType":"TCP","RemoteDns":"192.0.2.53","DomesticDNSType":"UDP","DomesticDns":"192.0.2.54","GlobalProxy":true}}""").asJsonObject
        dns?.let { document.add("dns", JsonParser.parseString(it.toJson())) }
        val link = "tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(document.toString().toByteArray(Charsets.UTF_8))
        val command = TunaRoutingLink.parse(link)
        if (dns != null) assertEquals(dns, command.policy.dns)
        val subscription = Subscription("fixture", "Fixture", "https://example.invalid/subscription",
            routingSource = command.source, useSubscriptionRouting = true)
        val profile = Profile("fixture-profile", "Fixture profile", subscriptionId = subscription.id)
        val effective = ProfileRouting.resolve(SharedNetworkSettings(), profile, listOf(subscription))
        assertEquals("", effective.dns.splitDnsJson)
        return JsonParser.parseString(XrayNetworkCompiler.compile(effective, hevEnabled = true).policyJson)
            .asJsonObject.getAsJsonObject("splitDns")
    }

    @Test fun inheritedSubscriptionCompilesAllSixRemoteAndTwoDirectResolvers() {
        fun resolver(index: Int, route: RouteAction) = SubscriptionPolicyParser.Resolver(
            "DOH", "https://dns$index.example.invalid/dns-query", null, route)
        val remote = (1..6).map { resolver(it, RouteAction.VPN) }
        val direct = (7..8).map { resolver(it, RouteAction.DIRECT) }
        val expected = SplitDnsSettings(remote = remote.first(), domestic = direct.first(),
            remoteServers = remote, domesticServers = direct, remoteSelectionMode = "priority",
            domesticSelectionMode = "auto", timeoutMs = 3500, queryStrategy = "UseIPv4",
            disableCache = true, hosts = mapOf("fixture.example.invalid" to "192.0.2.99"))

        val compiled = SplitDnsSettings.parse(compile(expected).toString())
        assertEquals("Inherited remote DNS pool must reach the native policy", 6, compiled.effectiveRemoteServers.size)
        assertEquals("Inherited direct DNS pool must reach the native policy", 2, compiled.effectiveDomesticServers.size)
        assertEquals(expected.copy(remoteEmergency = DnsRecoverySettings("system"),
            domesticEmergency = DnsRecoverySettings("system")), compiled)
    }

    @Test fun legacySubscriptionKeepsResolversAndMaterializesDocumentedEmergencyDefault() {
        val compiled = compile()
        assertEquals("192.0.2.53", compiled.getAsJsonObject("remote")["endpoint"].asString)
        assertEquals("192.0.2.54", compiled.getAsJsonObject("domestic")["endpoint"].asString)
        assertEquals("system", compiled.getAsJsonObject("remoteEmergency")["mode"].asString)
        assertEquals("system", compiled.getAsJsonObject("domesticEmergency")["mode"].asString)
        assertFalse("Legacy bootstrap still inherits the global user setting", compiled.has("bootstrap"))
    }
}
