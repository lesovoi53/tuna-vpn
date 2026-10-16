package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.kernel.XrayNetworkCompiler
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TcpOnlyDnsTest {
    @Test fun everyRemotePoolMemberAdaptsEvenWhenPrimaryIsDoh() {
        val original = SplitDnsSettings()
        val udp = original.remote.copy(transport = "UDP", endpoint = "192.0.2.53", bootstrapIp = null)
        val split = original.copy(remoteServers = listOf(original.remote, udp),
            domesticServers = listOf(original.domestic), remoteSelectionMode = "priority", timeoutMs = 3000)
        val actual = SplitDnsSettings.parse(resolve(SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = split.toJson()))).dns.splitDnsJson)
        assertEquals(listOf(original.remote, udp.copy(transport = "TCP")), actual.remoteServers)
        assertEquals(split.domesticServers, actual.domesticServers)
        assertEquals("priority", actual.remoteSelectionMode)
        assertEquals(3000, actual.timeoutMs)
        assertEquals("UDP", split.remoteServers!![1].transport)
    }

    private fun resolve(settings: SharedNetworkSettings, kernel: KernelConfig = KernelConfig.Vpn(VpnConfig.parse("naive+https://user:password@example.com")!!)) =
        ProfileRouting.resolve(settings, Profile("p", "Test", kernelConfig = kernel), emptyList())

    @Test fun freshInstallUsesSameResolversOverTcpWithoutPersistingChanges() {
        val original = SharedNetworkSettings()
        val effective = resolve(original)
        assertEquals("tcp://8.8.8.8,tcp://1.1.1.1", effective.dns.servers)
        assertEquals("8.8.8.8,1.1.1.1", original.dns.servers)
        assertEquals(effective.dns.servers, XrayNetworkCompiler.compile(effective, true).dnsServers)
        assertEquals(effective, resolve(effective))
    }

    @Test fun remoteUdpBecomesTcpWhileDomesticRouteAndHostsStayIntact() {
        val split = SplitDnsSettings().let { it.copy(remote = it.remote.copy(transport = "UDP", endpoint = "192.0.2.53:5353", bootstrapIp = null),
            hosts = mapOf("test.example" to "192.0.2.1"), queryStrategy = "UseIPv4", disableCache = true) }
        val effective = resolve(SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = split.toJson())))
        val actual = SplitDnsSettings.parse(effective.dns.splitDnsJson)
        assertEquals("TCP", actual.remote.transport)
        assertEquals(split.remote.endpoint, actual.remote.endpoint)
        assertEquals(split.domestic, actual.domestic)
        assertEquals(split.hosts, actual.hosts)
        assertEquals(split.queryStrategy, actual.queryStrategy)
        assertTrue(actual.disableCache)
        val compiled = JsonParser.parseString(XrayNetworkCompiler.compile(effective, true).policyJson).asJsonObject
        assertEquals(actual, SplitDnsSettings.parse(compiled["splitDns"].toString()))
    }

    @Test fun naiveIsTcpOnlyButOtherProtocolsAndDohRemainUnchanged() {
        val settings = SharedNetworkSettings()
        val naive = KernelConfig.Vpn(VpnConfig.parse("naive+https://user:password@example.com")!!)
        assertTrue(resolve(settings, naive).dns.servers.startsWith("tcp://"))
        assertEquals(settings, resolve(settings, KernelConfig.Vpn()))
        val doh = settings.copy(dns = NetworkDnsSettings(splitDnsJson = SplitDnsSettings().toJson()))
        assertEquals(doh, resolve(doh))
    }

    @Test fun naiveUdpOverTcpKeepsUdpDnsUnlessExplicitlyDisabled() {
        val settings = SharedNetworkSettings()
        val profile = VpnConfig.parse("naive+https://user:password@example.com?udp-over-tcp=true")!!
        assertEquals(settings, resolve(settings, KernelConfig.Vpn(profile)))
        val split = SplitDnsSettings().let { it.copy(remote = it.remote.copy(transport = "UDP", endpoint = "192.0.2.53", bootstrapIp = null)) }
        val custom = settings.copy(dns = NetworkDnsSettings(splitDnsJson = split.toJson()))
        assertEquals(custom, resolve(custom, KernelConfig.Vpn(profile)))
        val disabled = profile.copy(options = com.wireturn.app.data.kernel.NaiveUdpOverTcp.withEnabled(profile, false))
        assertTrue(resolve(settings, KernelConfig.Vpn(disabled)).dns.servers.startsWith("tcp://"))
        assertEquals("TCP", SplitDnsSettings.parse(resolve(custom, KernelConfig.Vpn(disabled)).dns.splitDnsJson).remote.transport)
    }

    @Test fun subscriptionKeepsItsRulesAndDomesticDnsDuringAdaptation() {
        val payload = """{"Name":"Test","RemoteDNSType":"UDP","RemoteDns":"192.0.2.53","DomesticDNSType":"UDP","DomesticDns":"192.0.2.54","BlockSites":["domain:blocked.example"]}"""
        val source = "happ://routing/onadd/" + java.util.Base64.getEncoder().encodeToString(payload.toByteArray())
        val sub = Subscription("s", "S", "https://example.invalid/sub", routingSource = source, useSubscriptionRouting = true)
        val profile = Profile("p", "Naive", kernelConfig = KernelConfig.Vpn(VpnConfig.parse("naive+https://user:password@example.com")!!), subscriptionId = "s")
        val effective = ProfileRouting.resolve(SharedNetworkSettings(), profile, listOf(sub))
        val split = SplitDnsSettings.parse(effective.dns.splitDnsJson)
        assertEquals("TCP", split.remote.transport)
        assertEquals("192.0.2.53", split.remote.endpoint)
        assertEquals("UDP", split.domestic.transport)
        assertEquals("192.0.2.54", split.domestic.endpoint)
        assertEquals(source, effective.routing.subscriptionSource)
        assertEquals(source, sub.routingSource)
        val compiled = XrayNetworkCompiler.compile(effective, true)
        val policy = JsonParser.parseString(compiled.policyJson).asJsonObject
        assertEquals(split, SplitDnsSettings.parse(policy["splitDns"].toString()))
        assertTrue(compiled.policyJson.contains("blocked.example"))
        assertEquals(source, compiled.subscriptionSource)
        val naive = profile.copy(kernelConfig = KernelConfig.Vpn(
            VpnConfig.parse("naive+https://user:password@example.com")!!))
        val naiveEffective = ProfileRouting.resolve(SharedNetworkSettings(), naive, listOf(sub))
        assertEquals(compiled, XrayNetworkCompiler.compile(naiveEffective, true))
    }
}
