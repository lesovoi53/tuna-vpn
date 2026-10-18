package com.wireturn.app.kernel

import com.wireturn.app.data.*
import com.wireturn.app.domain.DnsRecoverySettings
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.domain.SubscriptionPolicyParser
import org.junit.Assert.*
import org.junit.Test

class EffectiveDnsBootstrapTest {
    @Test fun hotReloadTracksPolicyBootstrapWithoutRestartingForMaskedGlobalChanges() {
        val global = SharedNetworkSettings(dns = NetworkDnsSettings(bootstrap = "tcp://192.0.2.1:53"))
        val system = global.copy(dns = global.dns.copy(splitDnsJson = SplitDnsSettings(bootstrap = DnsRecoverySettings("system")).toJson()))
        assertTrue(EffectiveDnsBootstrap.changed(global, system))
        val changedGlobal = system.copy(dns = system.dns.copy(bootstrap = "tcp://192.0.2.2:53"))
        assertFalse(EffectiveDnsBootstrap.changed(system, changedGlobal))
        val changedPool = system.copy(dns = system.dns.copy(splitDnsJson = SplitDnsSettings(
            bootstrap = DnsRecoverySettings("system"), timeoutMs = 2000).toJson()))
        assertFalse(EffectiveDnsBootstrap.changed(system, changedPool))
        // Raw profiles use the global value even after switching from a generated profile.
        assertFalse(EffectiveDnsBootstrap.changed(global, system, rawXray = true))
        assertTrue(EffectiveDnsBootstrap.changed(system, changedGlobal, rawXray = true))
        assertEquals(global.dns.bootstrap, EffectiveDnsBootstrap.resolve(system, rawXray = true))
    }

    @Test fun subscriptionBootstrapChangeTriggersReloadEvenWithUnchangedGlobalValue() {
        fun subscription(mode: String): SharedNetworkSettings {
            val payload = """{"Name":"Synthetic","RemoteDns":"192.0.2.53","DomesticDns":"192.0.2.54","TunaDNS":{"bootstrap":$mode}}"""
            return SharedNetworkSettings(routing = NetworkRoutingSettings(subscriptionSource = "happ://routing/onadd/" +
                java.util.Base64.getEncoder().encodeToString(payload.toByteArray())))
        }
        val system = subscription("""{"mode":"system"}""")
        val tcp = subscription("""{"mode":"resolver","resolver":{"transport":"TCP","endpoint":"192.0.2.60:53","route":"DIRECT"}}""")
        assertTrue(EffectiveDnsBootstrap.changed(system, tcp))
        assertFalse(EffectiveDnsBootstrap.changed(system, tcp, rawXray = true))
        assertEquals("tcp://192.0.2.60:53", EffectiveDnsBootstrap.resolve(tcp))
    }

    @Test fun explicitSystemReachesBothTransportAdapters() {
        val shared = SharedNetworkSettings(dns = NetworkDnsSettings(bootstrap = "tcp://192.0.2.1", splitDnsJson =
            SplitDnsSettings(bootstrap = DnsRecoverySettings("system")).toJson()))
        val actual = EffectiveDnsBootstrap.resolve(shared)
        assertEquals("system", actual)
        assertEquals("system", BootstrapDns.parse(actual)!!.xrayAddress())
        assertEquals("local", BootstrapDns.parse(actual)!!.singBoxServer()["type"].asString)
        assertFalse(BootstrapDns.parse(actual)!!.singBoxServer().has("server"))
    }
    @Test fun inheritAndDisableRetainGlobalBootstrap() {
        for (override in listOf("", "disabled", SplitDnsSettings().toJson())) {
            val shared = SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = override, bootstrap = "tcp://192.0.2.1"))
            assertEquals("tcp://192.0.2.1", EffectiveDnsBootstrap.resolve(shared))
        }
    }
    @Test fun customPreservesPortAndDirectRoute() {
        val split = SplitDnsSettings(bootstrap = DnsRecoverySettings("resolver",
            SubscriptionPolicyParser.Resolver("TCP", "[2001:db8::1]:5353", null, RouteAction.DIRECT)))
        val shared = SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = split.toJson()))
        assertEquals("tcp://[2001:db8::1]:5353", BootstrapDns.parse(EffectiveDnsBootstrap.resolve(shared))!!.xrayAddress())
    }
}
