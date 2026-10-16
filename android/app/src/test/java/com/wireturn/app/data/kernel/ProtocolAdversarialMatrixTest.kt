package com.wireturn.app.data.kernel

import java.net.URI
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

/** Import/editor contracts use reserved hosts and never launch a native core. */
class ProtocolAdversarialMatrixTest {
    private val uuid = "00000000-0000-4000-8000-000000000001"
    private val password = "p:+@/%2F &雪"
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun fixtures(): Map<String, String> {
        val secret = encode(password)
        return linkedMapOf(
            "vless" to "vless://$uuid@vpn.invalid:443?security=tls&type=xhttp&extra=${encode("""{"sessionIDKey":"x%2F&+"}""")}",
            "hysteria2" to "hy2://$secret@vpn.invalid:443?sni=keep.invalid&hop_interval=10",
            "snell" to "snell://$secret@vpn.invalid:443?version=4&reuse=true",
            "mieru" to "mierus://user:$secret@vpn.invalid/?profile=Name%2BKeep&port=443&protocol=TCP&port=1000-1002&protocol=UDP",
            "trojan" to "trojan://$secret@vpn.invalid:443?type=ws&path=%2Fkeep%3Fx%3D%252F&sni=keep.invalid",
            "shadowsocks" to "ss://aes-256-gcm:$secret@vpn.invalid:443?type=tcp",
            "tuic" to "tuic://$uuid:$secret@vpn.invalid:443?congestion_control=bbr&heartbeat=10s",
            "anytls" to "anytls://$secret@vpn.invalid:443?min_idle_session=5&idle_session_timeout=30s"
        )
    }

    @Test fun everyImporterRejectsInvalidPortsAndBrokenPercentEscapes() {
        for ((protocol, source) in fixtures()) {
            assertNotNull(protocol, VpnConfig.parse(source))
            for (port in listOf("0", "65536", "-1", "443.5", "999999999999")) {
                val bad = if (protocol == "mieru") source.replace("port=443", "port=$port")
                    else source.replace(":443?", ":$port?")
                assertNull("$protocol port $port", VpnConfig.parse(bad))
            }
            assertNull("$protocol broken percent escape", VpnConfig.parse(source.replace("@vpn.invalid", "%ZZ@vpn.invalid")))
        }
    }

    @Test fun percentEncodedDuplicateKeysCannotOverrideAnEarlierOption() {
        for ((protocol, source) in fixtures()) {
            // Mieru intentionally permits repeated port/protocol; profile must remain unique.
            val first = URI(source).rawQuery.substringBefore('&')
            val key = first.substringBefore('=')
            val alias = "%" + key.first().code.toString(16) + key.drop(1)
            assertNull("$protocol duplicate $key", VpnConfig.parse("$source&$alias=${first.substringAfter('=')}"))
        }
    }

    @Test fun editingImportedServerPreservesDecodedSecretsAndUnrelatedOptions() {
        for ((protocol, source) in fixtures()) {
            val imported = VpnConfig.parse(source)!!
            val edited = VpnEditorDraft.from(imported.uri).change("server", "edited.invalid").toUri()
            val parsed = VpnConfig.parse(edited)!!
            assertEquals(protocol, parsed.protocol)
            assertEquals("edited.invalid", parsed.serverLabel)
            assertEquals(URI(imported.uri).rawQuery, URI(edited).rawQuery)
            assertEquals(URI(imported.uri).rawUserInfo, URI(edited).rawUserInfo)
            assertEquals(edited, VpnEditorDraft.from(edited).toUri())
            if (protocol == "mieru") {
                assertEquals(password, VpnEditorDraft.from(edited).fields.single { it.key == "password" }.value)
            } else {
                val key = when (protocol) { "vless" -> "uuid"; "snell" -> "psk"; else -> "password" }
                assertEquals(if (protocol == "vless") uuid else password, parsed.outbound()[key].asString)
            }
        }
    }

    @Test fun extendedXhttpRejectsMalformedNestedDownloadPortsWithoutTruncationOrWraparound() {
        fun profile(extra: String) = VpnConfig(
            "vless://$uuid@vpn.invalid:443?security=tls&type=xhttp&extra=${encode(extra)}", "sing-box")
        for (port in listOf("1", "443", "65535")) {
            val valid = profile("""{"downloadSettings":{"address":"download.invalid","port":$port}}""")
            valid.validate()
            assertEquals(port.toInt(), valid.outbound().getAsJsonObject("transport").getAsJsonObject("download")["server_port"].asInt)
        }
        val invalid = listOf("0", "65536", "-1", "443.5", "4294967739", "true", "null", "[]", "{}")
        for (port in invalid) {
            assertFalse("Nested download port $port", profile("""{"downloadSettings":{"address":"download.invalid","port":$port}}""").isValid())
        }
        for (extra in listOf("""{"downloadSettings":[]}""", """{"xmux":true}""")) {
            assertFalse(extra, profile(extra).isValid())
        }
    }
}
