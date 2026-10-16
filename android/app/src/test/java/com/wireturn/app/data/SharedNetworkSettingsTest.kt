package com.wireturn.app.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.JsonParser
import com.wireturn.app.domain.NetworkPolicyResolver
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.domain.SubscriptionRoutingAssets
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SharedNetworkSettingsTest {
    private val source = "happ://routing/onadd/" + Base64.getEncoder().encodeToString("""{
        "Name":"Network fixture","GlobalProxy":"true","RouteOrder":"block-proxy-direct",
        "RemoteDNSType":"DoH","RemoteDNSDomain":"https://dns.google/dns-query","RemoteDNSIP":"8.8.8.8",
        "DomesticDNSType":"UDP","DomesticDns":"192.0.2.53",
        "BlockSites":["domain:blocked.example"],"DirectSites":["domain:direct.example"],
        "DnsHosts":{"host.example":"192.0.2.10"},"DomainStrategy":"IPIfNonMatch","FakeDNS":"false"
    }""".toByteArray())

    @Test fun oldPreferencesRoundTripWithoutChangingUnrelatedData() {
        val consent = booleanPreferencesKey("routing_disabled_after_confirmation_v1")
        val profiles = stringPreferencesKey("profiles_fixture")
        val p = mutablePreferencesOf(
            AppPreferences.XRAY_DNS to "tcp://192.0.2.53,1.1.1.1",
            AppPreferences.XRAY_SPLIT_DNS to "disabled",
            AppPreferences.XRAY_FAKEDNS to true,
            AppPreferences.XRAY_ROUTING_POLICY to " {\"mode\":\"ALL_VPN\",\"rules\":[],\"future\":7} ",
            AppPreferences.XRAY_ROUTE_DIRECT to "domain:direct.example\n192.0.2.0/24",
            AppPreferences.XRAY_ROUTE_BLOCK to "domain:blocked.example",
            AppPreferences.XRAY_HAPP_SOURCE to source,
            AppPreferences.XRAY_BYPASS_LAN to true,
            AppPreferences.XRAY_GEO_VARIANT to "roscomvpn",
            AppPreferences.XRAY_USER to "test-user", AppPreferences.XRAY_PASS to "test-secret",
            consent to true, profiles to "unchanged profiles and groups",
        )
        val before = p.toPreferences()
        NetworkPreferences.write(p, NetworkPreferences.read(p))
        assertEquals(before, p.toPreferences())
        NetworkPreferences.write(p, SharedNetworkSettings())
        assertEquals(true, p[consent])
        assertEquals(before[profiles], p[profiles])
        assertEquals("test-user", p[AppPreferences.XRAY_USER])
        assertEquals("test-secret", p[AppPreferences.XRAY_PASS])
    }

    @Test fun missingKeysReadDefaultsWithoutWritingOrDisablingRouting() {
        val p = mutablePreferencesOf()
        assertEquals(SharedNetworkSettings(), NetworkPreferences.read(p))
        assertTrue(p.asMap().isEmpty())
        NetworkPreferences.write(p, SharedNetworkSettings())
        assertNull(p[booleanPreferencesKey("routing_disabled_after_confirmation_v1")])
    }

    @Test fun oldSubscriptionWritersAndRestoreRemainVisible() {
        val p = mutablePreferencesOf(AppPreferences.XRAY_DNS to "1.1.1.1")
        val before = NetworkPreferences.read(p)
        // SubscriptionSettingsRegistry writes the same storage keys directly.
        p[stringPreferencesKey("xray_dns")] = "192.0.2.53"
        p[stringPreferencesKey("xray_split_dns")] = "disabled"
        val updated = NetworkPreferences.read(p)
        assertEquals("192.0.2.53", updated.dns.servers)
        assertEquals(SplitDnsOverride.DISABLED, updated.dns.overrideMode)
        NetworkPreferences.write(p, before)
        assertEquals(before, NetworkPreferences.read(p))
    }

    @Test fun legacyBridgeRetainsEveryFieldAndDnsOverrideState() {
        for (override in listOf("", "  ", "disabled", SplitDnsSettings().toJson())) {
            val old = XraySettings(socksBindAddress = "127.0.0.1:15000", httpBindAddress = "127.0.0.1:15001",
                isProxyAuthEnabled = false, proxyUser = "local", proxyPass = "secret",
                dns = "192.0.2.53", routeDirect = "domain:direct.example", routeBlock = "domain:blocked.example",
                routingPolicyJson = "{\"mode\":\"ALL_VPN\",\"rules\":[]}", happRoutingSource = source,
                splitDnsJson = override, bypassLan = true, fakeDns = true, geoVariant = "roscomvpn")
            assertEquals(old, SharedNetworkSettings.fromLegacy(old).applyToLegacy(old))
        }
    }

    @Test fun profileSwitchDoesNotChangeGlobalNetworkPolicy() {
        val p = mutablePreferencesOf(AppPreferences.XRAY_HAPP_SOURCE to source,
            AppPreferences.XRAY_SPLIT_DNS to "disabled")
        val before = NetworkPreferences.read(p)
        p[AppPreferences.ACTIVE_KERNEL_JSON] = "{\"profile\":\"next-fixture\"}"
        p[AppPreferences.ACTIVE_XRAY_ENABLED] = true
        assertEquals(before, NetworkPreferences.read(p))
    }

    @Test fun legacyRouterChoiceMigratesToXrayWithoutChangingConsent() {
        val p = mutablePreferencesOf(AppPreferences.XRAY_DNS to "192.0.2.53")
        val next = SharedNetworkSettings(router = NetworkRouter.SING_BOX_EXTENDED)
        NetworkPreferences.write(p, next)
        assertEquals(next.copy(router = NetworkRouter.XRAY), NetworkPreferences.read(p))
        assertNull(p[booleanPreferencesKey("routing_disabled_after_confirmation_v1")])
        assertTrue(runCatching { com.wireturn.app.kernel.XrayNetworkCompiler.compile(next, true) }.isFailure)
    }

    @Test fun legacyRulesRetainBlockFirstOrderAndLanBeforeFallback() {
        val s = SharedNetworkSettings(routing = NetworkRoutingSettings(
            legacyDirect = "domain:direct.example,192.0.2.0/24", legacyBlock = "domain:blocked.example", bypassLan = true))
        val resolved = NetworkPolicyResolver.resolve(s)
        assertEquals(listOf(RouteAction.BLOCK, RouteAction.DIRECT, RouteAction.DIRECT), resolved.routing.rules.map { it.action })
        val applied = RoutingPolicy.load(resolved.applyOverrides(resolved.routing.toJson()), "", "")
        assertEquals(resolved.routing.rules, applied.rules.take(3))
        assertEquals("10.0.0.0/8", applied.rules[3].match)
        assertEquals("VPN", applied.fallback)
    }

    @Test fun subscriptionWinsOverLocalRulesAndDnsOverridesKeepHosts() {
        val s = SharedNetworkSettings(routing = NetworkRoutingSettings(
            policyJson = "invalid unused local policy", subscriptionSource = source))
        val resolved = NetworkPolicyResolver.resolve(s)
        assertNotNull(resolved.subscription)
        val base = SubscriptionRoutingAssets.nativePolicy(source)
        assertEquals(base, resolved.applyOverrides(base))
        val custom = NetworkPolicyResolver.resolve(s.copy(dns = NetworkDnsSettings(splitDnsJson = SplitDnsSettings().toJson())))
        val root = JsonParser.parseString(custom.applyOverrides(base)).asJsonObject
        assertEquals("192.0.2.10", root.getAsJsonObject("splitDns").getAsJsonObject("hosts")["host.example"].asString)
        assertEquals("IPIfNonMatch", root["domainStrategy"].asString)
        val disabled = NetworkPolicyResolver.resolve(s.copy(dns = NetworkDnsSettings(splitDnsJson = "disabled")))
        assertFalse(JsonParser.parseString(disabled.applyOverrides(base)).asJsonObject.has("splitDns"))
    }

    @Test fun fakeDnsGateMatchesExistingRuntime() {
        for (override in listOf("", "disabled", SplitDnsSettings().toJson())) {
            for (subscription in listOf("", source)) {
                val s = SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = override, fakeDns = true),
                    routing = NetworkRoutingSettings(subscriptionSource = subscription))
                assertEquals(override.isEmpty() && subscription.isEmpty(), NetworkPolicyResolver.resolve(s).useLegacyFakeDns)
            }
        }
    }

    @Test fun corruptDataIsRetainedForEditingButNeverSilentlyReplacedAtLaunch() {
        val p = mutablePreferencesOf(AppPreferences.XRAY_ROUTING_POLICY to "invalid",
            AppPreferences.XRAY_SPLIT_DNS to "also invalid")
        val s = NetworkPreferences.read(p)
        assertEquals("invalid", s.routing.policyJson)
        assertEquals("also invalid", s.dns.splitDnsJson)
        assertTrue(runCatching { NetworkPolicyResolver.resolve(s) }.isFailure)
        val dnsOnly = NetworkPolicyResolver.resolve(s.copy(routing = NetworkRoutingSettings()))
        assertTrue(runCatching { dnsOnly.applyOverrides(dnsOnly.routing.toJson()) }.isFailure)
    }
}
