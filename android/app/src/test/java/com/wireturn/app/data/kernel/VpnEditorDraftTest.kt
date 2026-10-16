package com.wireturn.app.data.kernel

import org.junit.Assert.*
import org.junit.Test

class VpnEditorDraftTest {
    @Test fun everyManualProtocolCreatesAnEditableValidProfile() {
        assertEquals(setOf("vless", "hysteria2", "snell", "mieru", "trojan", "shadowsocks", "tuic", "anytls", "naive", "awg", "wireguard", "trusttunnel"),
            VpnEditorDraft.manualProtocols.map { it.first }.toSet())
        for ((protocol, _) in VpnEditorDraft.manualProtocols) {
            var draft = VpnEditorDraft.create(protocol)
            assertEquals("", draft.fields.first { it.key == "server" }.value)
            assertTrue(runCatching { draft.toUri() }.isFailure)
            draft = draft.change("server", "example.com")
                .change("credential", if (protocol == "vless") "00000000-0000-4000-8000-000000000001" else "test-password")
                .change("password", "test-password")
            if (protocol == "tuic") draft = draft.change("username", "00000000-0000-4000-8000-000000000001")
            if (protocol in setOf("mieru", "naive", "trusttunnel")) draft = draft.change("username", "test-user")
            if (protocol in setOf("awg", "wireguard")) {
                fun key(n: Byte) = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { n })
                for ((key,value) in mapOf("private_key" to key(7), "public_key" to key(8)))
                    draft = draft.change(draft.fields.first { it.queryName == key }.key, value)
            }
            val uri = draft.toUri()
            val parsed = VpnConfig.parse(uri)
            assertNotNull(protocol, parsed)
            assertEquals(protocol, parsed!!.protocol)
            assertEquals(uri, VpnEditorDraft.from(uri).toUri())
            parsed.validate()
        }
    }

    @Test fun importedMieruMtuIsSelectedAndPreserved() {
        val uri = "mierus://user:pass@example.com/?profile=Test&port=1000&protocol=TCP&mtu=1377"
        assertNotNull(VpnConfig.parse(uri))
        val draft = VpnEditorDraft.from(uri)
        val field = draft.fields.first { it.label == "MTU" }
        assertEquals("1377", field.value)
        assertTrue(field.options.containsAll(listOf("", "1280", "1377", "1500")))
        assertFalse(field.options.contains("1501"))
        assertEquals(uri, draft.toUri())
        assertNotNull(VpnConfig.parse(draft.change(field.key, "1400").toUri()))
        assertNotNull(VpnConfig.parse(draft.change(field.key, "0").toUri()))
        assertNull(VpnConfig.parse(draft.change(field.key, "1501").toUri()))
    }
    @Test fun vlessFingerprintRemainsEditableWithoutRewritingTransport() {
        for (transport in listOf("tcp", "xhttp")) {
            val uri = "vless://00000000-0000-4000-8000-000000000001@example.com:443?type=$transport&security=tls&fp=firefox"
            val draft = VpnEditorDraft.from(uri)
            val field = draft.fields.first { it.label == "TLS fingerprint" }
            assertEquals("firefox", field.value)
            assertEquals("fp", field.queryName)
            assertEquals(uri, draft.toUri())
            assertNotNull(VpnConfig.parse(draft.change(field.key, "chrome").toUri()))
        }
    }
    @Test fun untouchedUriAndOpaqueOptionsArePreserved() {
        val uri = "vless://00000000-0000-4000-8000-000000000001@example.com:443?type=xhttp&security=tls&extra=%7B%22sessionIDKey%22%3A%22x%22%7D#Name"
        val draft = VpnEditorDraft.from(uri)
        assertEquals(uri, draft.toUri())
        val changed = draft.change("server", "new.example.com").toUri()
        assertEquals(uri.replace("@example.com", "@new.example.com"), changed)
        assertNotNull(VpnConfig.parse(changed))
    }
    @Test fun mieruRepeatedPortsAndEncodedCredentialsSurviveEditing() {
        val uri = "mierus://user:pass%3A%2B@example.com/?profile=Test&port=1000-1002&protocol=TCP&port=2000&protocol=UDP&multiplexing=MULTIPLEXING_HIGH"
        val changed = VpnEditorDraft.from(uri).change("username", "new:user").toUri()
        assertTrue(changed.contains("new%3Auser:pass%3A%2B@"))
        assertTrue(changed.contains("port=1000-1002&protocol=TCP&port=2000&protocol=UDP"))
        assertNotNull(VpnConfig.parse(changed))
    }
    @Test fun editingSnellAndHy2ChangesRealOutbound() {
        val snell = "snell://key@example.com:443/?version=4&reuse=true#Test"
        val edited = VpnEditorDraft.from(snell).change("credential", "new:@key").change("add:obfs-mode", "http").change("add:obfs-host", "example.org").toUri()
        val outbound = VpnConfig.parse(edited)!!.outbound()
        assertEquals("new:@key", outbound["psk"].asString)
        assertEquals("example.org", outbound["obfs_host"].asString)
        val hy2 = VpnEditorDraft.from("hy2://pass@example.com:443/#Test").change("add:hop_interval", "10").toUri()
        assertEquals("10s", VpnConfig.parse(hy2)!!.outbound()["hop_interval"].asString)
    }
}
