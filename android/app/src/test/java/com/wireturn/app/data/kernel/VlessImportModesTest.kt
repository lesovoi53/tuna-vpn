package com.wireturn.app.data.kernel

import com.wireturn.app.kernel.XrayVpnConfig
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class VlessImportModesTest {
    private val base = "vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443?"

    @Test fun importsSubscriptionFragmentWithoutLosingItsParameters() {
        val uri = base + "security=tls&type=ws&headerType=&host=example.org&fragment=3-3%2C0-1%2Ctlshello"
        val profile = requireNotNull(VpnConfig.parse(uri))
        assertEquals(uri, profile.uri)
        val edited = VpnEditorDraft.from(uri).change("server", "new.invalid").toUri()
        val generated = XrayVpnConfig.generate(profile.copy(uri = edited), "127.0.0.1:1080", "u", "p", false)
        val mask = generated.getAsJsonObject("outbound_patch").getAsJsonObject("streamSettings").getAsJsonObject("finalmask")
        val settings = mask.getAsJsonArray("tcp")[0].asJsonObject.getAsJsonObject("settings")
        assertEquals("tlshello", settings["packets"].asString)
        assertEquals("3-3", settings["length"].asString)
        assertFalse(URI(generated["link"].asString).rawQuery.contains("fragment="))
    }

    @Test fun supportsPinnedWrapperVlessModesAndRefusesLossyCombinations() {
        for (query in listOf("type=tcp&security=none&headerType=http&host=example.org&path=%2F",
            "type=grpc&security=tls&mode=multi&authority=example.org&serviceName=test",
            "type=kcp&security=none&seed=test&mtu=1350&tti=20",
            "type=ws&security=tls&headers=%7B%22X-Test%22%3A%22yes%22%7D")) {
            val profile = requireNotNull(VpnConfig.parse(base + query)) { query }
            assertEquals(base + query, profile.uri)
            assertNotNull(XrayVpnConfig.generate(profile, "127.0.0.1:1080", "u", "p", false))
        }
        for (query in listOf("type=grpc&security=tls&extra=%7B%7D", "type=tcp&security=none&headerType=unknown",
            "type=kcp&security=none&seed=test&headerType=dns", "type=ws&security=tls&headers=broken")) {
            assertNull(query, VpnConfig.parse(base + query))
        }
    }

    @Test fun repairsOnlyMarkdownEscapesInSnellAuthority() {
        val escaped = "snell://test\\_secret\\@vpn.invalid:27305/?version=6&reuse=true&tfo=true&udp-relay=true&mode=default#Test"
        val parsed = requireNotNull(VpnConfig.parse(escaped))
        assertEquals("test_secret", parsed.outbound()["psk"].asString)
        assertFalse(parsed.uri.contains('\\'))
        assertNull(VpnConfig.parse(escaped.replace("test\\_secret", "test\\qsecret")))
        val literal = requireNotNull(VpnConfig.parse(escaped.replace("test\\_secret", "test%5C_secret")))
        assertEquals("test\\_secret", literal.outbound()["psk"].asString)
    }

    @Test fun writesPinnedCoreCompatibilityMatrixFixtures() {
        val directory = java.io.File("build/vless-mode-fixtures").apply { mkdirs() }
        val realityKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        for (transport in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "kcp")) {
            for (security in listOf("none", "tls", "reality")) {
                var uri = base + "type=$transport&security=$security&encryption=none"
                if (security != "none") uri += "&sni=example.org&fp=firefox"
                if (security == "reality") uri += "&pbk=$realityKey&sid=abcd"
                if (transport == "grpc") uri += "&serviceName=test&authority=example.org&mode=multi"
                if (transport == "xhttp") uri += "&mode=auto&path=%2Ftest"
                if (security == "reality" && transport in setOf("ws", "httpupgrade", "kcp")) {
                    assertFalse(VpnConfig(uri, "xray").isValid())
                    // Remove only this test's obsolete generated fixture after a matrix change.
                    java.io.File(directory, "$transport-$security.json").delete()
                    continue
                }
                val config = requireNotNull(VpnConfig.parse(uri)).copy(engine = "xray")
                java.io.File(directory, "$transport-$security.json").writeText(
                    XrayVpnConfig.generate(config, "127.0.0.1:1080", "u", "p", false).toString())
            }
        }
        for (mode in listOf("tls", "tcp", "both")) {
            val config = requireNotNull(VpnConfig.parse(base + "type=ws&security=tls&fp=firefox&sni=example.org"))
                .copy(options = VpnOptions(fragmentation = VpnFragmentation(mode, "3-3", "0")))
            java.io.File(directory, "fragment-$mode.json").writeText(
                XrayVpnConfig.generate(config, "127.0.0.1:1080", "u", "p", false).toString())
        }
        for (flow in listOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")) for (security in listOf("tls", "reality")) {
            val query = "type=tcp&security=$security&fp=firefox&sni=example.org&flow=$flow" +
                if (security == "reality") "&pbk=$realityKey&sid=abcd" else ""
            val config = requireNotNull(VpnConfig.parse(base + query)).copy(engine = "xray")
            java.io.File(directory, "flow-$flow-$security.json").writeText(
                XrayVpnConfig.generate(config, "127.0.0.1:1080", "u", "p", false).toString())
            assertFalse(config.copy(uri = config.uri + "&mux=4").isValid())
        }
        val encryptionKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184) { 1 })
        for (encryptionMode in listOf("native", "xorpub", "random")) for (rtt in listOf("0rtt", "1rtt")) {
            val config = requireNotNull(VpnConfig.parse(base + "type=tcp&security=none&encryption=mlkem768x25519plus.$encryptionMode.$rtt.$encryptionKey"))
            java.io.File(directory, "encryption-$encryptionMode-$rtt.json").writeText(
                XrayVpnConfig.generate(config, "127.0.0.1:1080", "u", "p", false).toString())
        }
    }
}
