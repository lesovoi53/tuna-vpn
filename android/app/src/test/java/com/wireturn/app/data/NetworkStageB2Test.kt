package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.JsonArray
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.domain.GeoRuleSets
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.kernel.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

class NetworkStageB2Test {
    private val listener = SingBoxNetworkCompiler.LocalSocks("127.0.0.1:19080", "fixture-in", "fixture-password")
    private val upstream = SingBoxNetworkCompiler.LocalSocks("127.0.0.1:19081", "fixture-out", "fixture-password")
    private val profile = VpnConfig("vless://00000000-0000-4000-8000-000000000001@vpn.example:443?security=tls&sni=vpn.example")

    @Test fun bootstrapIsLiteralAndIdenticalInBothAdapters() {
        val dns = "tcp://[2001:db8::53]:5353"
        val extended = SingBoxConfig.generate(profile, listener.address, listener.username, listener.password, bootstrapDns = dns)
        val server = extended.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject
        assertEquals("2001:db8::53", server["server"].asString)
        assertEquals(5353, server["server_port"].asInt)
        val xray = XrayVpnConfig.generate(profile, listener.address, listener.username, listener.password, false, dns)
        assertEquals("8.8.8.8,1.1.1.1", xray["dns"].asString) // Bootstrap is an environment setting, not tunnel DNS.
        assertEquals(dns, BootstrapDns.parse(dns)!!.xrayAddress())
        val dir = File("build/network-compiler-fixtures").apply { mkdirs() }
        File(dir, "extended-bootstrap.json").writeText(extended.toString())
        File(dir, "xray-bootstrap.json").writeText(xray.toString())
        for (bad in listOf("tcp://dns.example", "udp://1.1.1.1", "tcp://1.1.1.1:0", "tcp://u:p@1.1.1.1", "tcp://1.1.1.1/path", "tcp://999.0.0.1")) {
            assertTrue(bad, runCatching { BootstrapDns.parse(bad) }.isFailure)
        }
    }

    @Test fun bootstrapDefaultsAndPersistenceDoNotOverwriteLegacyValues() {
        assertNull(BootstrapDns.parse(""))
        val prefs = mutablePreferencesOf(AppPreferences.XRAY_DNS to "192.0.2.53")
        val first = NetworkPreferences.read(prefs)
        val next = first.copy(dns = first.dns.copy(bootstrap = "tcp://192.0.2.54"), router = NetworkRouter.SING_BOX_EXTENDED)
        NetworkPreferences.write(prefs, next)
        val oldView = NetworkPreferences.read(prefs).applyToLegacy(XraySettings(proxyUser = "keep", proxyPass = "keep"))
        assertEquals(next.copy(router = NetworkRouter.XRAY), SharedNetworkSettings.fromLegacy(oldView))
        assertEquals("192.0.2.53", oldView.dns)
        assertEquals("keep", oldView.proxyUser)
        val before = prefs.toPreferences()
        assertTrue(runCatching { NetworkPreferences.write(prefs, next.copy(dns = next.dns.copy(bootstrap = "tcp://bad.example"))) }.isFailure)
        assertEquals(before, prefs.toPreferences())
    }

    private fun number(value: Int): ByteArray {
        var n = value
        val bytes = mutableListOf<Byte>()
        do { var b = n and 127; n = n ushr 7; if (n != 0) b = b or 128; bytes += b.toByte() } while (n != 0)
        return bytes.toByteArray()
    }
    private fun bytes(id: Int, value: ByteArray) = number(id * 8 + 2) + number(value.size) + value
    private fun text(id: Int, value: String) = bytes(id, value.toByteArray())
    private fun int(id: Int, value: Int) = number(id * 8) + number(value)
    private fun domain(type: Int, value: String, attr: String? = null): ByteArray = bytes(2,
        int(1, type) + text(2, value) + (attr?.let { bytes(3, text(1, it) + int(2, 0)) } ?: byteArrayOf()))

    @Test fun geoConversionRetainsTypesAttributesCidrsAndRuntimeOrder() {
        val dir = Files.createTempDirectory("tuna-geodata-test").toFile()
        try {
            File(dir, "geosite.dat").writeBytes(bytes(1, text(1, "DEMO") +
                domain(0, "substring") + domain(1, "^regex\\.example$") + domain(2, "suffix.example", "keep") + domain(3, "full.example")))
            File(dir, "geoip.dat").writeBytes(bytes(1, text(1, "DEMO") + bytes(2,
                bytes(1, byteArrayOf(192.toByte(), 0, 2, 0)) + int(2, 24))))
            val matches = listOf("geosite:demo", "geosite:demo@keep", "geoip:demo")
            val converted = GeoRuleSets.load(dir, matches)
            assertEquals("substring", converted.getValue("geosite:demo").getAsJsonArray("domain_keyword")[0].asString)
            assertEquals(4, converted.getValue("geosite:demo").size())
            assertEquals(1, converted.getValue("geosite:demo@keep").size())
            assertEquals("192.0.2.0/24", converted.getValue("geoip:demo").getAsJsonArray("ip_cidr")[0].asString)
            val policy = RoutingPolicy(rules = listOf(RoutingRule("geosite:demo", RouteAction.BLOCK),
                RoutingRule("geoip:demo", RouteAction.DIRECT), RoutingRule("2001:db8::/32", RouteAction.VPN)))
            val settings = SharedNetworkSettings(dns = NetworkDnsSettings(splitDnsJson = SplitDnsSettings().toJson()),
                routing = NetworkRoutingSettings(policyJson = policy.toJson(), bypassLan = true), router = NetworkRouter.SING_BOX_EXTENDED)
            val config = SingBoxNetworkCompiler.compile(settings, listener, upstream, converted)
            val route = config.getAsJsonObject("route")
            assertEquals("geosite:demo", route.getAsJsonArray("rules")[2].asJsonObject.getAsJsonArray("rule_set")[0].asString)
            assertEquals(2, route.getAsJsonArray("rule_set").size())
            assertEquals(1, config.getAsJsonObject("dns").getAsJsonArray("rules").size()) // Only geosite: never use geoip as a DNS answer rule.
            assertFalse(route.getAsJsonArray("rules").any { it.asJsonObject["action"]?.asString == "resolve" })
            File("build/network-compiler-fixtures/extended-geo-lan.json").apply { parentFile!!.mkdirs() }.writeText(config.toString())
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingEmptyCorruptAndInvertedGeoCategoriesCannotMatchEverything() {
        val dir = Files.createTempDirectory("tuna-invalid-geodata").toFile()
        try {
            val file = File(dir, "geosite.dat")
            for (data in listOf(bytes(1, text(1, "demo")), bytes(1, text(1, "other") + domain(3, "example")), byteArrayOf(10, 127, 0))) {
                file.writeBytes(data)
                assertTrue(runCatching { GeoRuleSets.load(dir, listOf("geosite:demo")) }.isFailure)
            }
            File(dir, "geoip.dat").writeBytes(bytes(1, text(1, "demo") + int(3, 1)))
            assertTrue(runCatching { GeoRuleSets.load(dir, listOf("geoip:demo")) }.isFailure)
        } finally { dir.deleteRecursively() }
    }

    @Test fun readinessRejectsUnauthenticatedSocksListener() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val bytes = ByteArray(3)
                    java.io.DataInputStream(socket.getInputStream()).readFully(bytes)
                    socket.getOutputStream().write(byteArrayOf(5, 0))
                }
            }
            try {
                assertTrue(runCatching { RouterSocksProbe.verify(listener.copy(address = "127.0.0.1:${server.localPort}")) }.isFailure)
            } finally { worker.join(2000) }
        }
    }
}
