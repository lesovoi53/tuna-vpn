package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.domain.*
import com.wireturn.app.kernel.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI

class Stage12Test {
    private val vless = "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=tls&sni=example.com"
    private val hy = "hy2://secret@192.0.2.1:443?sni=example.com"
    @Test fun engineSelectionPersistsAndRejectsIncompatibleConfigurations() {
        for (uri in listOf(vless, hy)) for (engine in listOf("auto", "sing-box", "xray")) {
            val profile = VpnConfig(uri, engine)
            assertTrue(profile.isValid())
            assertEquals(profile, Gson().fromJson(Gson().toJson(profile), VpnConfig::class.java))
            assertEquals(engine != "sing-box", profile.usesXray)
        }
        assertEquals("auto", Gson().fromJson("{\"uri\":\"$vless\"}", VpnConfig::class.java).selectedEngine)
        assertFalse(VpnConfig("snell://secret@192.0.2.1:443?version=4", "xray").isValid())
        assertTrue(VpnConfig("$vless&type=xhttp", "sing-box").isValid())
        assertTrue(VpnConfig("$vless&type=xhttp").usesXray)
        assertFalse(VpnConfig("$hy&insecure=true", "xray").isValid())
        assertTrue(VpnConfig("$hy&insecure=true", "sing-box").isValid())
    }
    @Test fun muxUsesWrapperFieldAndPreservesServerName() {
        val profile = VpnConfig(vless.replace("sni=", "serverName=") + "&mux=8")
        val output = XrayVpnConfig.generate(profile, "127.0.0.1:2081", "test", "test", false)
        assertEquals(8, output["mux"].asInt)
        assertTrue(output["link"].asString.contains("sni=example.com"))
        assertFalse(output["link"].asString.contains("mux="))
        assertFalse(VpnConfig("$vless&flow=xtls-rprx-vision&mux=8").isValid())
        assertFalse(VpnConfig("$vless&type=xhttp&mux=8").isValid())
        assertTrue(VpnConfig("$vless&flow=xtls-rprx-vision&mux=0").isValid())
    }
    @Test fun vlessEchPreservesConfigAndRejectsUnsupportedForceQuery() {
        val encoded = "AAT+DQAA"
        val profile = VpnConfig("$vless&ech=AAT%2BDQAA", "xray")
        assertTrue(profile.isValid())
        val out = XrayVpnConfig.generate(profile, "127.0.0.1:2081", "test", "test", false)
        assertTrue(out["link"].asString.contains("ech=AAT%2BDQAA"))
        assertEquals(encoded, VpnConfig.decode("AAT%2BDQAA"))
        assertFalse(VpnConfig("${profile.uri}&echForceQuery=full").isValid())
        assertFalse(VpnConfig(profile.uri.replace("security=tls", "security=reality") + "&pbk=test").isValid())
        File("build/xray-test-configs").mkdirs()
        File("build/xray-test-configs/vless-ech.json").writeText(out.toString())
    }
    @Test fun hoppingUsesEachNativeSchema() {
        val profile = VpnConfig("$hy&server_ports=443,20000-20100&hop_interval=5&hop_interval_max=10&obfs-password=mask", "xray")
        assertTrue(profile.isValid())
        val out = profile.outbound()
        assertEquals("443:443", out.getAsJsonArray("server_ports")[0].asString)
        assertEquals("20000:20100", out.getAsJsonArray("server_ports")[1].asString)
        assertEquals("10.0s", out["hop_interval_max"].asString)
        val wrapper = XrayVpnConfig.generate(profile, "127.0.0.1:2081", "test", "test", false)
        val params = URI(wrapper["link"].asString).rawQuery.split('&').associate { VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('=')) }
        val hop = JsonParser.parseString(params.getValue("fm")).asJsonObject.getAsJsonArray("udp")[0].asJsonObject.getAsJsonObject("settings")
        assertEquals("443,20000-20100", hop["remotePorts"].asString)
        assertEquals("5-10", hop["interval"].asString)
        assertEquals("salamander", params["obfs"])
        val dir = File("build/vpn-test-configs").apply { mkdirs() }
        File(dir, "hy2-hopping.json").writeText(SingBoxConfig.generate(profile.copy(engine="sing-box"), "127.0.0.1:2081", "test", "test").toString())
        val xr = File("build/xray-test-configs").apply { mkdirs() }
        File(xr, "hy2-hopping.json").writeText(wrapper.toString())
        File(xr, "vless-mux.json").writeText(XrayVpnConfig.generate(VpnConfig("$vless&mux=8"), "127.0.0.1:2081", "test", "test", false).toString())
    }
    @Test fun hoppingRejectsBadRangesAndIntervals() {
        for (value in listOf("0", "65536", "100-1", "1,,2", "-1", "abc")) assertFalse(VpnConfig("$hy&server_ports=$value").isValid())
        for (suffix in listOf("hop_interval=0", "hop_interval=999999", "hop_interval=10&hop_interval_max=5", "hop_interval_max=10", "server_ports=443&mport=444")) assertFalse(VpnConfig("$hy&$suffix").isValid())
        assertFalse(VpnConfig("$hy&server_ports=443,444&hop_interval=0.5", "xray").isValid())
        assertTrue(VpnConfig("$hy&server_ports=443,444&hop_interval=0.5", "sing-box").isValid())
    }
    @Test fun hostsOverrideCanBeClearedButLegacySettingsInherit() {
        val settings = SplitDnsSettings()
        val base = """{"mode":"ALL_VPN","rules":[],"splitDns":{"hosts":{"old.example":"192.0.2.2"}}}"""
        fun hosts(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonObject("splitDns").getAsJsonObject("hosts")
        assertTrue(hosts(SplitDnsSettings.applyToPolicy(base, settings.toJson())).has("old.example"))
        assertEquals(0, hosts(SplitDnsSettings.applyToPolicy(base, settings.copy(hosts=emptyMap()).toJson())).size())
        val custom = StaticHosts.parse("Example.COM. 192.0.2.1\nrouter.example fd00::1")
        assertEquals("192.0.2.1", custom["example.com"])
        assertEquals(custom, SplitDnsSettings.parse(settings.copy(hosts=custom).toJson()).hosts)
        assertThrows(IllegalArgumentException::class.java) { StaticHosts.parse("example.com example.org") }
        assertThrows(IllegalArgumentException::class.java) { StaticHosts.parse("EXAMPLE.com 192.0.2.1\nexample.com 192.0.2.2") }
    }
    @Test fun lanPreservesUserPriorityAndAllVpnInactiveRules() {
        val rules = listOf(RoutingRule("10.0.0.0/16", RouteAction.BLOCK), RoutingRule("10.2.0.0/16", RouteAction.VPN))
        val policy = RoutingOverrides.withLan(RoutingPolicy(rules=rules), true)
        assertEquals(rules, policy.rules.take(2))
        assertEquals(RouteAction.BLOCK, RouteInspector.inspect(policy,"10.0.2.3").action)
        assertEquals(RouteAction.VPN, RouteInspector.inspect(policy,"10.2.2.3").action)
        assertEquals(RouteAction.DIRECT, RouteInspector.inspect(policy,"10.3.2.3").action)
        assertEquals(RouteAction.VPN, RouteInspector.inspect(policy,"192.0.2.1").action)
        val all = RoutingOverrides.withLan(RoutingPolicy(RoutingMode.ALL_VPN, rules), true)
        assertEquals(RouteAction.DIRECT, RouteInspector.inspect(all,"10.2.2.3").action)
        assertThrows(IllegalArgumentException::class.java) { RoutingOverrides.withLan(RoutingPolicy(rules=List(500) { rules[0] }),true) }
    }
    @Test fun inspectorRespectsDomainOrderAndUnknownGeo() {
        val p = RoutingPolicy(rules=listOf(RoutingRule("full:ads.example.com",RouteAction.BLOCK), RoutingRule("domain:example.com",RouteAction.DIRECT)))
        assertEquals(RouteAction.BLOCK, RouteInspector.inspect(p,"ads.example.com").action)
        assertEquals(RouteAction.DIRECT, RouteInspector.inspect(p,"www.example.com").dnsAction)
        assertEquals(RouteAction.VPN, RouteInspector.inspect(p,"example.net").action)
        assertNull(RouteInspector.inspect(p.copy(rules=listOf(RoutingRule("geosite:unknown",RouteAction.BLOCK))),"example.com").action)
        assertTrue(RouteInspector.cidrContains("fc00::/7","fd00::1"))
        assertFalse(RouteInspector.cidrContains("fc00::/7","2001:db8::1"))
    }
    @Test fun inspectorReadsGeoCategoryAndAttributes() {
        fun field(tag: Int, value: ByteArray) = byteArrayOf(((tag shl 3) or 2).toByte(), value.size.toByte()) + value
        val directory = java.nio.file.Files.createTempDirectory("tuna-geo-test").toFile()
        try {
            val item = byteArrayOf(8,2) + field(2,"example.com".toByteArray()) + field(3,field(1,"ads".toByteArray()))
            File(directory,"geosite.dat").writeBytes(field(1,field(1,"example".toByteArray())+field(2,item)))
            val p = RoutingPolicy(rules=listOf(RoutingRule("geosite:example@ads",RouteAction.BLOCK)))
            assertEquals(RouteAction.BLOCK, RouteInspector.inspect(p,"www.example.com",geoDir=directory).action)
            assertEquals(RouteAction.VPN, RouteInspector.inspect(p,"example.net",geoDir=directory).action)
            assertEquals(RouteAction.VPN, RouteInspector.inspect(p.copy(rules=listOf(RoutingRule("geosite:example@other",RouteAction.BLOCK))),"example.com",geoDir=directory).action)
        } finally { directory.listFiles()?.forEach { it.delete() };directory.delete() }
    }
}
