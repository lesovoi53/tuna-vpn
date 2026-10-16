package com.wireturn.app.data.kernel

import com.google.gson.GsonBuilder
import com.wireturn.app.data.*
import com.wireturn.app.kernel.SingBoxConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VpnConfigTest {
    @Test fun hysteriaEchIsForwardedAsPemAndInvalidLengthIsRejected() {
        val data = byteArrayOf(0, 4, 0xfe.toByte(), 0x0d, 0, 0)
        val encoded = java.util.Base64.getEncoder().encodeToString(data)
        val uri = "hy2://test@vpn.example:443?ech=$encoded"
        val ech = VpnConfig.parse(uri)!!.outbound().getAsJsonObject("tls").getAsJsonObject("ech")
        assertTrue(ech["enabled"].asBoolean)
        val pem = ech.getAsJsonArray("config")[0].asString
        assertTrue(pem.contains("-----BEGIN ECH CONFIGS-----"))
        assertTrue(pem.contains(encoded))
        assertNull(VpnConfig.parse("hy2://test@vpn.example:443?ech=AAAA"))
    }

    @Test fun domainEndpointsUseExplicitBootstrapDnsOutsideTheTunnel() {
        val profile = VpnConfig.parse("hy2://test-password@vpn.example.com:8443?sni=vpn.example.com")!!
        val config = SingBoxConfig.generate(profile, "127.0.0.1:2081", "user", "pass")
        val dns = config.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject
        assertEquals("tcp", dns["type"].asString)
        assertEquals("1.1.1.1", dns["server"].asString)
        assertFalse(dns.has("detour"))
        assertEquals("vpn", config.getAsJsonObject("route")["final"].asString)
        File("build/vpn-test-configs").mkdirs()
        File("build/vpn-test-configs/hy2-domain.json").writeText(config.toString())
    }
    @Test fun hysteriaSharedLinksInferSalamanderFromPasswordAndKeepTlsVerification() {
        val uri = "hysteria2://test-password@192.0.2.1:8443?obfs-password=test-obfs&security=tls&sni=example.com&alpn=h3&fp=chrome"
        val config = VpnConfig.parse(uri)!!
        val out = config.outbound()
        assertEquals(uri, config.uri)
        assertEquals("salamander", out.getAsJsonObject("obfs")["type"].asString)
        assertEquals("test-obfs", out.getAsJsonObject("obfs")["password"].asString)
        assertFalse(out.getAsJsonObject("tls").has("utls"))
        assertFalse(out.getAsJsonObject("tls").has("insecure"))
        assertNull(VpnConfig.parse(uri.replace("security=tls", "security=none")))
        assertNull(VpnConfig.parse("$uri&obfs=unknown"))
        assertFalse(VpnConfig.parse(uri.replace("obfs-password=test-obfs&", ""))!!.outbound().has("obfs"))
    }
    @Test fun importsMieruRangesAndAllSupportedMaskSettings() {
        val base = "mierus://test:test-password@192.0.2.1/?profile=Test%20Mieru&port=20715-20735&protocol=TCP&multiplexing=MULTIPLEXING_HIGH"
        for (mode in MieruUri.lowEntropyModes) for (rotation in MieruUri.rotations) {
            val uri = "$base&low-entropy-mode=$mode&low-entropy-mask-rotation=$rotation"
            val config = VpnConfig.parse(uri)!!
            assertEquals(uri, config.uri)
            assertEquals("Test Mieru", config.displayName)
            assertEquals("mieru", config.protocol)
            assertTrue(KernelConfig.parseUri(uri) is KernelConfig.Vpn)
        }
        assertNull(VpnConfig.parse("$base&low-entropy-mode=UNKNOWN"))
        assertNull(VpnConfig.parse("$base&low-entropy-mask-rotation=UNKNOWN"))
        assertNull(VpnConfig.parse(base.replace("20715-20735", "20735-20715")))
        assertNull(VpnConfig.parse("$base&port=1234"))
        assertNotNull(VpnConfig.parse("$base&port=1234&protocol=UDP"))
        assertNull(VpnConfig.parse("$base&mtu=1279"))
        assertNotNull(VpnConfig.parse("$base&mtu=1280"))
        assertNotNull(VpnConfig.parse("$base&mtu=1500"))
        assertNull(VpnConfig.parse("$base&mtu=1501"))
    }
    private val snell = "snell://test%2Bsecret@192.0.2.1:19552/?version=4&obfs-mode=http&obfs-host=example.com&reuse=true#Test%20profile"
    @Test fun importsSnellWithoutLosingObfuscationReuseOrEscaping() {
        val config = VpnConfig.parse(snell)!!
        val out = config.outbound()
        assertEquals("test+secret", out["psk"].asString)
        assertEquals("http", out["obfs_mode"].asString)
        assertEquals("example.com", out["obfs_host"].asString)
        assertTrue(out["reuse"].asBoolean)
        assertEquals("Test profile", config.displayName)
        assertFalse(out.has("network"))
        assertFalse(out.has("tcp_fast_open"))
        val client = ClientConfig(kernelConfig = KernelConfig.Vpn(config)).fillDefaults()
        assertTrue(client.isValid)
        assertTrue(client.isSocksAuthEnabled)
        val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
        assertEquals(client, gson.fromJson(gson.toJson(client), ClientConfig::class.java))
        assertEquals(client.kernelConfig, KernelConfig.parseUri(snell))
    }
    @Test fun refusesLossyOrAmbiguousImports() {
        for (suffix in listOf("&unknown=1", "&version=4", "&network=bad", "&obfs-host=bad")) assertNull(VpnConfig.parse(snell.substringBefore('#') + suffix))
        assertNull(VpnConfig.parse(snell.replace("version=4", "version=6")))
        assertNull(VpnConfig.parse(snell.replace("reuse=true", "reuse=perhaps")))
        assertNull(VpnConfig.parse(snell.replace(":19552", ":0")))
    }
    @Test fun importsCompatibleSnellV4AndV5() {
        for (version in listOf(4, 5)) {
            assertNull(VpnConfig.parse(snell.replace("version=4", "version=6")))
            val uri = VpnConfig.parse(snell.replace("version=4", "version=$version"))!!
            assertEquals(snell.replace("version=4", "version=$version"), uri.uri)
            assertEquals(4, uri.outbound()["version"].asInt)
            val line = "Test Snell = snell, 192.0.2.1, 1488, psk=test+secret, version=$version, reuse=true, tfo=true, udp-relay=true"
            val profile = KernelConfig.parseUri(line) as KernelConfig.Vpn
            val out = profile.config.outbound()
            assertEquals(4, out["version"].asInt)
            assertEquals("test+secret", out["psk"].asString)
            assertTrue(out["tcp_fast_open"].asBoolean)
            assertTrue(out["reuse"].asBoolean)
            assertFalse(out.has("network"))
            assertEquals("Test Snell", profile.config.displayName)
            assertNull(VpnConfig.parse("$line, version=4"))
            assertNull(VpnConfig.parse("$line, unknown=true"))
        }
        assertEquals("tcp", VpnConfig.parse(snell.substringBefore('#') + "&udp-relay=false")!!.outbound()["network"].asString)
        assertNull(VpnConfig.parse(snell.substringBefore('#') + "&udp-relay=false&network=udp"))
    }
    @Test fun standaloneProtocolsProduceNativeConfigurationFixtures() {
        val uris = listOf(snell,
            "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=tls&sni=example.com&type=ws&path=%2Ftest",
            "hy2://test-password@192.0.2.1:443?sni=example.com&obfs=salamander&obfs-password=test-obfs")
        val dir = File("build/vpn-test-configs").apply { mkdirs() }
        uris.forEach { uri ->
            val config = VpnConfig.parse(uri)!!
            val result = SingBoxConfig.generate(config, "127.0.0.1:2081", "local-user", "local-pass")
            assertEquals("vpn", result.getAsJsonObject("route")["final"].asString)
            File(dir, "${config.protocol}.json").writeText(result.toString())
        }
    }
    @Test fun rejectsMixedMissingAndDuplicateGroupMembers() {
        val vpn = Profile("vpn", "VPN", KernelConfig.Vpn(VpnConfig(snell)))
        val bypass = Profile("bypass", "qWDTT", KernelConfig.Qwdtt())
        val group = ConnectionGroup("g", "Test", ConnectionCategory.VPN, GroupTestType.URL_TEST, listOf("vpn", "vpn2"))
        group.validate(listOf(vpn, vpn.copy(id = "vpn2"), bypass))
        for (members in listOf(listOf("vpn", "bypass"), listOf("missing"), listOf("vpn", "vpn"))) {
            assertThrows(IllegalArgumentException::class.java) { group.copy(memberIds = members).validate(listOf(vpn, bypass)) }
        }
        assertEquals(ConnectionCategory.BYPASS, bypass.category)
        assertEquals(ConnectionCategory.VPN, vpn.category)
    }
}
