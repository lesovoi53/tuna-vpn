package com.wireturn.app.kernel

import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.TrustTunnelUri
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TrustTunnelIpv4OnlyTest {
    private val base = "tt://synthetic:password@example.invalid:443?security=tls"

    @Test fun bothTransportsAcceptIpv4OnlyAndKeepCapabilityAfterEditing() {
        for (quic in listOf(false, true)) {
            val fields = listOf(
                0 to byteArrayOf(1), 1 to "example.invalid".toByteArray(),
                2 to "example.invalid:443".toByteArray(), 4 to byteArrayOf(0),
                5 to "synthetic".toByteArray(), 6 to "password".toByteArray(),
                9 to byteArrayOf(if (quic) 2 else 1)
            )
            val bytes = fields.flatMap { (tag, value) ->
                listOf(tag.toByte(), value.size.toByte()) + value.toList()
            }.toByteArray()
            val profile = requireNotNull(VpnConfig.parse("tt://?" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
            assertTrue(TrustTunnelUri.ipv4Only(profile.uri))
            val edited = com.wireturn.app.data.kernel.VpnEditorDraft.from(profile.uri).change("port", "8443").toUri()
            assertTrue(TrustTunnelUri.ipv4Only(requireNotNull(VpnConfig.parse(edited)).uri))
            val config = SingBoxConfig.generate(profile, "127.0.0.1:1080", "test", "password")
            val rules = config.getAsJsonObject("route").getAsJsonArray("rules")
            assertEquals("resolve", rules[0].asJsonObject["action"].asString)
            assertEquals("ipv4_only", rules[0].asJsonObject["strategy"].asString)
            assertEquals(6, rules[1].asJsonObject["ip_version"].asInt)
            assertEquals("reject", rules[1].asJsonObject["action"].asString)
            assertEquals("ipv4_only", config.getAsJsonObject("dns")["strategy"].asString)
            assertEquals(1, config.getAsJsonArray("outbounds").size())
            assertEquals("trusttunnel", config.getAsJsonArray("outbounds")[0].asJsonObject["type"].asString)
            assertFalse(profile.outbound().has("has_ipv6"))
        }
    }

    @Test fun dualStackRemainsUnrestrictedAndMalformedFlagIsRejected() {
        for (suffix in listOf("", "&has_ipv6=true", "&has_ipv6=1")) {
            val profile = requireNotNull(VpnConfig.parse(base + suffix))
            assertFalse(TrustTunnelUri.ipv4Only(profile.uri))
            assertFalse(SingBoxConfig.generate(profile, "127.0.0.1:1080", "test", "password")
                .getAsJsonObject("route").has("rules"))
        }
        assertNotNull(VpnConfig.parse("$base&has_ipv6=0"))
        assertNull(VpnConfig.parse("$base&has_ipv6=invalid"))
        assertNull(VpnConfig.parse("$base&has_ipv6=false&has_ipv6=true"))
    }
}
