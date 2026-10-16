package com.wireturn.app.data.kernel

import com.google.gson.JsonParser
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.kernel.SingBoxConfig
import org.junit.Assert.*
import org.junit.Test

class NaiveUriTest {
    private val link = "naive+https://user%3Aname:p%40ss%3Aword+%2F%3D@example.com:8443#Naive%20test"

    @Test fun importsWithCredentialsAndDeterministicEngine() {
        val profile = VpnConfig.parse(link)!!
        assertEquals("naive", profile.protocol)
        assertEquals("sing-box", profile.engine)
        assertEquals(link, profile.uri)
        assertEquals("Naive test", profile.displayName)
        assertTrue(KernelConfig.parseUri(link) is KernelConfig.Vpn)
        val out = profile.outbound()
        assertEquals("user:name", out["username"].asString)
        assertEquals("p@ss:word+/=", out["password"].asString)
        assertEquals(8443, out["server_port"].asInt)
        assertTrue(out.getAsJsonObject("tls")["enabled"].asBoolean)
        assertFalse(out.getAsJsonObject("tls").has("utls"))
    }

    @Test fun defaultHttpsPortAndIpv6AreValid() {
        val out = VpnConfig.parse("naive+https://user:password@[2001:db8::1]/?security=tls&sni=example.com")!!.outbound()
        assertEquals("2001:db8::1", out["server"].asString)
        assertEquals(443, out["server_port"].asInt)
        assertEquals("example.com", out.getAsJsonObject("tls")["server_name"].asString)
    }

    @Test fun editingOneFieldPreservesOtherAuthenticationAndQueryValues() {
        val original = link.substringBefore('#') + "?security=tls&sni=example.net#Name"
        val edited = VpnEditorDraft.from(original).change("password", "новый:@+/%").toUri()
        val out = VpnConfig.parse(edited)!!.outbound()
        assertEquals("user:name", out["username"].asString)
        assertEquals("новый:@+/%", out["password"].asString)
        assertEquals("example.net", out.getAsJsonObject("tls")["server_name"].asString)
        assertEquals(edited, VpnEditorDraft.from(edited).toUri())
    }

    @Test fun malformedAndUnsupportedOptionsFailWithoutDirectFallback() {
        val base = "naive+https://user:password@example.com:443"
        for (link in listOf("https://user:password@example.com", "naive+quic://user:password@example.com",
            "naive+https://example.com", "naive+https://user:@example.com", "$base/other",
            "$base?security=none", "$base?fp=firefox", "$base?insecure=true", "$base?alpn=h2",
            "$base?sni=a&sni=b", "$base?extra-headers=%7B%22X-Test%22%3A%22a%0D%0Ab%22%7D")) {
            assertNull(link.substringBefore('@'), VpnConfig.parse(link))
        }
    }

    @Test fun authenticatedSocksAndSingleNaiveOutbound() {
        val config = SingBoxConfig.generate(VpnConfig.parse(link)!!, "127.0.0.1:2081", "local-user", "local-pass")
        assertEquals(1, config.getAsJsonArray("outbounds").size())
        assertEquals("naive", config.getAsJsonArray("outbounds")[0].asJsonObject["type"].asString)
        assertEquals("vpn", config.getAsJsonObject("route")["final"].asString)
        assertEquals(1, config.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonArray("users").size())
    }

    @Test fun nativeOptionsCannotTurnOnIncompatibleTlsOrTransport() {
        for (patch in listOf("""{"tls":{"insecure":true}}""", """{"tls":{"alpn":["h2"]}}""",
            """{"tls":{"utls":{"enabled":true,"fingerprint":"firefox"}}}""", """{"quic":true}""",
            """{"transport":{"type":"ws"}}""")) {
            assertTrue(runCatching { VpnConfig(link, "sing-box", VpnOptions(singBox = JsonParser.parseString(patch).asJsonObject)).validate() }.isFailure)
        }
    }

    @Test fun udpOverTcpImportsBothSpellingsAndSurvivesEditing() {
        val base = link.substringBefore('#')
        for (query in listOf("udp-over-tcp=true", "udp_over_tcp=1", "udp-over-tcp=true&udp-over-tcp-version=1",
            "udp_over_tcp=true&udp_over_tcp_version=2")) {
            val profile = VpnConfig.parse("$base?$query#UDP")!!
            assertTrue(NaiveUdpOverTcp.enabled(profile))
            val edited = VpnEditorDraft.from(profile.uri).change("password", "replacement").toUri()
            assertTrue(NaiveUdpOverTcp.enabled(VpnConfig.parse(edited)!!))
            assertEquals(profile.outbound()["udp_over_tcp"], VpnConfig.parse(edited)!!.outbound()["udp_over_tcp"])
            val native = SingBoxConfig.generate(profile, "127.0.0.1:2081", "local-user", "local-pass")
            assertEquals(profile.outbound()["udp_over_tcp"], native.getAsJsonArray("outbounds")[0].asJsonObject["udp_over_tcp"])
        }
        val draft = VpnEditorDraft.from(base).change("add:udp-over-tcp", "true").change("add:udp-over-tcp-version", "2")
        assertTrue(NaiveUdpOverTcp.enabled(VpnConfig.parse(draft.toUri())!!))
        assertFalse(NaiveUdpOverTcp.enabled(VpnConfig.parse(link)!!))
        assertFalse(NaiveUdpOverTcp.enabled(VpnConfig.parse("$base?udp-over-tcp=false")!!))
        val versioned = VpnEditorDraft.from("$base?udp-over-tcp=true&udp-over-tcp-version=1")
        assertFalse(NaiveUdpOverTcp.enabled(VpnConfig.parse(versioned.change("q:0", "false").toUri())!!))
        val aliasDraft = VpnEditorDraft.from("$base?udp_over_tcp=true&udp_over_tcp_version=1")
        assertEquals(2, aliasDraft.fields.count { it.queryName?.startsWith("udp") == true })
        assertFalse(NaiveUdpOverTcp.enabled(VpnConfig.parse(aliasDraft.change("q:0", "false").toUri())!!))
    }

    @Test fun udpOverTcpRejectsMalformedLinkAndNativeSettings() {
        val base = link.substringBefore('#')
        for (query in listOf("udp-over-tcp=yes", "udp-over-tcp=true&udp_over_tcp=true",
            "udp-over-tcp=true&udp-over-tcp=true", "udp-over-tcp-version=2",
            "udp-over-tcp=true&udp-over-tcp-version=3")) {
            assertNull(query, VpnConfig.parse("$base?$query"))
        }
        for (value in listOf("1", "\"true\"", "[]", "null", "{\"enabled\":\"true\"}",
            "{\"enabled\":true,\"version\":3}", "{\"enabled\":true,\"version\":1.5}",
            "{\"enabled\":true,\"version\":\"2\"}", "{\"enabled\":true,\"unknown\":true}")) {
            val options = VpnOptions(singBox = JsonParser.parseString("{\"udp_over_tcp\":$value}").asJsonObject)
            assertTrue(value, runCatching { VpnConfig(link, options = options).validate() }.isFailure)
        }
    }

    @Test fun switchPreservesVersionAndOtherSettingsAndSurvivesShareRoundTrip() {
        val source = link.substringBefore('#') + "?udp-over-tcp=true&udp-over-tcp-version=1"
        val original = VpnConfig.parse(source)!!.copy(options = VpnOptions(singBox =
            JsonParser.parseString("""{"connect_timeout":"9s"}""").asJsonObject))
        assertFalse(NaiveUdpOverTcp.enabled(original.copy(options = VpnOptions(singBox =
            JsonParser.parseString("""{"udp_over_tcp":false}""").asJsonObject))))
        val disabled = original.copy(options = NaiveUdpOverTcp.withEnabled(original, false))
        assertFalse(NaiveUdpOverTcp.enabled(disabled))
        assertEquals(source, disabled.uri)
        val enabled = disabled.copy(options = NaiveUdpOverTcp.withEnabled(disabled, true))
        assertTrue(NaiveUdpOverTcp.enabled(enabled))
        assertEquals(1, enabled.outbound().getAsJsonObject("udp_over_tcp")["version"].asInt)
        assertEquals("9s", enabled.outbound()["connect_timeout"].asString)
        val gson = com.google.gson.GsonBuilder().registerTypeAdapter(KernelConfig::class.java,
            com.wireturn.app.data.KernelConfigAdapter()).create()
        val shared = com.wireturn.app.domain.ProfileEncoder.encode(gson.toJson(KernelConfig.Vpn(enabled), KernelConfig::class.java))
        val restored = gson.fromJson(com.wireturn.app.domain.ProfileEncoder.decode(shared), KernelConfig::class.java) as KernelConfig.Vpn
        restored.config.validate()
        assertEquals(enabled.outbound(), restored.config.outbound())
        val field = CoreCapabilities.fields("sing-box", "naive").single { it.path == "udp_over_tcp" }
        assertEquals(JsonParser.parseString("true"), field.parse("true"))
        assertEquals(JsonParser.parseString("{\"enabled\":true,\"version\":2}"), field.parse("{\"enabled\":true,\"version\":2}"))
    }

    @Test fun snellV5KeepsSourceAndFlagsButUsesCompatibleClient() {
        val uri = "snell://test-key@192.0.2.1:19552/?version=5&reuse=true&tfo=true#Snell-v5"
        val profile = VpnConfig.parse(uri)!!
        assertEquals(uri, profile.uri)
        assertEquals(uri, VpnEditorDraft.from(uri).toUri())
        val out = profile.outbound()
        assertEquals(4, out["version"].asInt)
        assertTrue(out["reuse"].asBoolean)
        assertTrue(out["tcp_fast_open"].asBoolean)
    }
}
