package com.wireturn.app.data.kernel

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.kernel.XrayVpnConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AdditionalVpnProtocolsTest {
    @Test fun blankManualDraftCannotSavePlaceholderServerOrPassword() {
        for (protocol in AdditionalVpnProtocols.protocols) {
            val draft = VpnEditorDraft.create(protocol)
            assertEquals("", draft.fields.first { it.key == "server" }.value)
            assertTrue(runCatching { draft.toUri() }.isFailure)
            val filled = draft.change("server", "vpn.example").change("credential", "secret")
                .change("password", "secret").let { if (protocol == "tuic") it.change("username", uuid) else it }
            assertNotNull(VpnConfig.parse(filled.toUri()))
        }
    }
    private val uuid = "00000000-0000-4000-8000-000000000001"
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test fun ss2022PreservesMultiUserKeyChainAndPercentEncoding() {
        val a = Base64.getEncoder().encodeToString(ByteArray(32) { 0xfb.toByte() })
        val b = Base64.getEncoder().encodeToString(ByteArray(32) { 42 })
        val p = VpnConfig.parse("ss://2022-blake3-aes-256-gcm:$a:$b@vpn.example:443?type=tcp#Test")!!
        assertEquals("$a:$b", p.outbound()["password"].asString)
        val edited = VpnEditorDraft.from(p.uri).change("port", "8443").toUri()
        assertEquals("$a:$b", VpnConfig.parse(edited)!!.outbound()["password"].asString)
        assertNull(VpnConfig.parse("ss://2022-blake3-aes-256-gcm:short@vpn.example:443"))
    }

    @Test fun sip002AndLegacyLinksRoundTripWithoutDoubleDecoding() {
        val credentials = "aes-256-gcm:p+ass:%2F/="
        for (uri in listOf("ss://${b64(credentials)}@vpn.example:443#Test", "ss://${b64("$credentials@vpn.example:443")}#Test")) {
            val p = VpnConfig.parse(uri)!!
            assertEquals("p+ass:%2F/=", p.outbound()["password"].asString)
            assertEquals(p, VpnConfig.parse(p.uri))
            assertTrue(KernelConfig.parseUri(uri) is KernelConfig.Vpn)
        }
    }

    @Test fun trojanTlsAndCredentialsReachBothEngines() {
        val p = VpnConfig.parse("trojan://p%2B%2F%3D@vpn.example:443?security=tls&sni=sni.example&fp=firefox&alpn=h2%2Chttp%2F1.1&type=tcp")!!
        assertEquals("p+/=", p.outbound()["password"].asString)
        assertEquals(2, p.outbound().getAsJsonObject("tls").getAsJsonArray("alpn").size())
        assertNull(CoreCapabilities.compatibility(p)["xray"])
        val generated = XrayVpnConfig.generate(p.copy(engine="xray"), "127.0.0.1:22001", "u", "p", false)
        assertEquals("p+/=", generated.getAsJsonObject("outbound_patch").getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject["password"].asString)
        assertEquals(p.uri, XrayVpnConfig.generate(p.copy(engine="xray"), "127.0.0.1:22001", "u", "p", false)["link"].asString)
        assertNull(VpnConfig.parse(p.uri + "&security=none"))
    }

    @Test fun trojanGrpcAuthorityImportsAndSurvivesEditingAndXrayGeneration() {
        val uri = "trojan://fixture%2Bpassword@vpn.example:443?security=tls&type=grpc&serviceName=tunnel&authority=grpc.example#Fixture"
        val parsed = (KernelConfig.parseUri(uri) as? KernelConfig.Vpn)?.config
        assertNotNull(parsed)
        val profile = requireNotNull(parsed)
        assertEquals("xray", profile.engine)
        val generated = XrayVpnConfig.generate(profile, "127.0.0.1:22001", "u", "p", false)
        assertEquals(uri, generated["link"].asString)
        val edited = VpnEditorDraft.from(uri).change("port", "8443").toUri()
        assertTrue(edited.contains("authority=grpc.example"))
        assertNotNull(KernelConfig.parseUri(edited))
        assertFalse(profile.copy(engine = "sing-box").isValid())
        assertTrue(runCatching { profile.outbound() }.isFailure)
    }

    @Test fun trojanAuthorityRequiresGrpcAndCannotBeSilentlyIgnored() {
        for (transport in listOf("tcp", "ws", "httpupgrade")) {
            assertNull(VpnConfig.parse("trojan://fixture@vpn.example:443?security=tls&type=$transport&authority=grpc.example"))
        }
        assertNull(VpnConfig.parse("trojan://fixture@vpn.example:443?type=grpc&authority=%0A"))
    }

    @Test fun tuicValidatesQuicOptionsAndSeparateCredentialFields() {
        val p = VpnConfig.parse("tuic://$uuid:p%3A%2B@vpn.example:443?congestion_control=bbr&udp_relay_mode=native&allow_insecure=0&alpn=h3%2Cspdy%2F3.1")!!
        assertEquals("p:+", p.outbound()["password"].asString)
        assertFalse(p.outbound().getAsJsonObject("tls")["insecure"].asBoolean)
        val edited = VpnEditorDraft.from(p.uri).change("password", "new:+@").toUri()
        assertEquals("new:+@", VpnConfig.parse(edited)!!.outbound()["password"].asString)
        assertFalse(p.copy(engine="xray").isValid())
        assertNull(VpnConfig.parse(p.uri + "&fp=firefox"))
        assertNull(VpnConfig.parse(p.uri + "&heartbeat=-1s"))
    }

    @Test fun anytlsKeepsSessionOptionsAndRejectsUnknowns() {
        val uri = "anytls://secret@vpn.example:443/?idle_session_check_interval=30s&idle_session_timeout=30s&min_idle_session=5&sni=vpn.example"
        val p = VpnConfig.parse(uri)!!
        assertEquals(5, p.outbound()["min_idle_session"].asInt)
        assertEquals("30s", p.outbound()["idle_session_timeout"].asString)
        assertEquals(uri, VpnEditorDraft.from(uri).toUri())
        assertNull(VpnConfig.parse(uri + "&unknown=1"))
        assertNull(VpnConfig.parse(uri + "&insecure=1&allowInsecure=0"))
        assertNull(VpnConfig.parse("ss://aes-256-gcm:secret@vpn.example:443?plugin=missing"))
    }
}
