package com.wireturn.app.kernel

import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.domain.ProfileRouting
import com.wireturn.app.domain.SplitDnsSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SnellDestinationResolverTest {
    private val snell = KernelConfig.Vpn(VpnConfig.parse("snell://test-psk@192.0.2.1:8800?version=5&obfs-mode=http&obfs-host=Ya.ru&reuse=true")!!)

    @Test fun hevMapDnsDestinationsAlwaysUseClientDns() {
        val vless = KernelConfig.Vpn(VpnConfig.parse(
            "vless://00000000-0000-4000-8000-000000000001@example.com:443?security=tls")!!)
        assertTrue(SnellDestinationResolver.forTunnel(vless, fakeDnsEnabled = false, hevEnabled = true))
        assertTrue(SnellDestinationResolver.forTunnel(KernelConfig.Webdav(), fakeDnsEnabled = false, hevEnabled = true))
        assertFalse(SnellDestinationResolver.forTunnel(vless, fakeDnsEnabled = false, hevEnabled = false))
    }

    @Test fun onlySnellNeedsTheDestinationResolver() {
        assertTrue(SnellDestinationResolver.required(snell))
        assertFalse(SnellDestinationResolver.required(KernelConfig.Vpn(VpnConfig.parse(
            "vless://00000000-0000-4000-8000-000000000001@example.com:443?security=tls")!!)))
        assertFalse(SnellDestinationResolver.required(KernelConfig.Webdav()))
    }

    @Test fun fakeDnsUdpForNewSocksKernelsNeedsResolutionInTheLiveRouterOnly() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val links = listOf(
            "tt://user:password@example.com:443?security=tls",
            "wg://192.0.2.1:51820?private_key=$key&public_key=$key&local_address=10.0.0.2%2F32",
            "wg://192.0.2.1:51820?private_key=$key&public_key=$key&local_address=10.0.0.2%2F32&enable_amnezia=true"
        )
        links.forEach { link ->
            val config = KernelConfig.Vpn(VpnConfig.parse(link)!!)
            assertTrue(SnellDestinationResolver.forTunnel(config, fakeDnsEnabled = true))
            assertTrue(SnellDestinationResolver.forTunnel(config, fakeDnsEnabled = false, hevEnabled = true))
            assertFalse(SnellDestinationResolver.forTunnel(config, fakeDnsEnabled = false))
            // Profile checks without FakeDNS keep the profile's own DNS behavior.
            assertFalse(SnellDestinationResolver.required(config))
        }
        assertTrue(SnellDestinationResolver.forTunnel(snell, fakeDnsEnabled = false))
        assertFalse(SnellDestinationResolver.forTunnel(KernelConfig.Webdav(), fakeDnsEnabled = true))
        val vless = KernelConfig.Vpn(VpnConfig("vless://test@example.com:443"))
        assertFalse(SnellDestinationResolver.forTunnel(vless, fakeDnsEnabled = true))
    }

    @Test fun healthProbeRespectsDisabledUpstreamAuthenticationAndWildcardBind() {
        val config = ClientConfig(socksAddr = "0.0.0.0:1080", socksUser = "fixture-user",
            socksPass = "fixture-password", isSocksAuthEnabled = false, kernelConfig = snell)
        assertEquals("127.0.0.1:1080", SnellDestinationResolver.upstream(config))
        assertEquals("fixture-user:fixture-password@127.0.0.1:1080",
            SnellDestinationResolver.upstream(config.copy(isSocksAuthEnabled = true)))
        assertFalse(config.isSocksAuthEnabled)
    }

    @Test fun isolatedProbeKeepsRemoteDnsAndStaticHostsButNeverUsesDirectTestRoute() {
        val dns = SplitDnsSettings(hosts = mapOf("probe.example" to "192.0.2.44"), queryStrategy = "UseIPv6", disableCache = true)
        val network = SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = dns.toJson()),
            routing = NetworkRoutingSettings(policyJson = """{"mode":"SELECTED_VPN","rules":[{"match":"domain:probe.example","action":"DIRECT","enabled":true}]}"""))
        val original = network.toString()
        val policy = JsonParser.parseString(SnellDestinationResolver.probePolicy(network)).asJsonObject
        assertEquals("ALL_VPN", policy["mode"].asString)
        assertEquals(0, policy["rules"].asJsonArray.size())
        assertEquals(dns, SplitDnsSettings.parse(policy["splitDns"].toString()))
        assertEquals(original, network.toString())
    }

    @Test fun subscriptionDnsIsUsedForThatProfileWithoutChangingTheAccount() {
        val payload = """{"Name":"Test","RemoteDNSType":"TCP","RemoteDns":"192.0.2.53","DomesticDNSType":"UDP","DomesticDns":"192.0.2.54","BlockSites":["domain:blocked.example"]}"""
        val source = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(payload.toByteArray())
        val sub = Subscription("s", "S", "https://example.invalid/sub", routingSource = source, useSubscriptionRouting = true)
        val profile = Profile("p", "Snell", kernelConfig = snell, subscriptionId = "s")
        val account = SharedNetworkSettings()
        val policy = JsonParser.parseString(SnellDestinationResolver.probePolicy(ProfileRouting.resolve(account, profile, listOf(sub)))).asJsonObject
        val dns = SplitDnsSettings.parse(policy["splitDns"].toString())
        assertEquals("192.0.2.53", dns.remote.endpoint)
        assertEquals("TCP", dns.remote.transport)
        assertEquals("", account.routing.subscriptionSource)
        assertFalse(JsonParser.parseString(SnellDestinationResolver.probePolicy(account)).asJsonObject.has("splitDns"))
    }
}
