package com.wireturn.app.data.kernel

import com.wireturn.app.kernel.SingBoxConfig
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class VlessXhttpCompatibilityTest {
    private val base = "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?type=xhttp&security=reality&sni=example.com&fp=firefox&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=0123456789abcdef"
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test fun repeatedModeAndCookieAliasesSurviveExtendedMapping() {
        val extra = """{"mode":"stream-up","seqKey":"rq","seqPlacement":"cookie","sessionIDKey":"sess","sessionKey":"sess","sessionIDPlacement":"cookie","sessionPlacement":"cookie","sessionIDLength":"16-32","sessionIDTable":"Base62","xPaddingBytes":"100-1000","xPaddingKey":"sid","xPaddingMethod":"tokenish","xPaddingObfsMode":true,"xPaddingPlacement":"cookie"}"""
        val profile = VpnConfig("$base&mode=stream-up&path=%2Fapi%2Fv1%2Fstream&extra=${enc(extra)}", "sing-box")
        profile.validate()
        val t = profile.outbound().getAsJsonObject("transport")
        assertEquals("stream-up", t["mode"].asString)
        assertEquals("cookie", t["session_placement"].asString)
        assertEquals("sess", t["session_key"].asString)
        assertEquals("rq", t["seq_key"].asString)
        assertEquals("tokenish", t["x_padding_method"].asString)
        File("build/core-capabilities-fixtures").mkdirs()
        File("build/core-capabilities-fixtures/extended-xhttp-cookie.json").writeText(SingBoxConfig.generate(profile, "127.0.0.1:21089", "test", "test").toString())
        assertFalse(VpnConfig("$base&mode=stream-up&extra=${enc("""{"mode":"packet-up"}""")}", "sing-box").isValid())
        assertEquals("stream-up", VpnConfig("$base&extra=${enc(extra)}", "sing-box").outbound().getAsJsonObject("transport")["mode"].asString)
    }

    @Test fun spiderPathAndLongEncryptionKeyArePreservedForBothEngines() {
        // Canonical all-zero polynomial encoding for a public, non-secret fixture.
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184))
        val encryption = "mlkem768x25519plus.xorpub.0rtt.$key"
        val uri = "$base&mode=stream-up&spx=%2Ffallback&encryption=$encryption"
        val profile = VpnConfig(uri, "sing-box", VpnOptions(singBox = NativeOptions.parse("""{"tls":{"reality":{"support_x25519mlkem768":true}}}""")))
        profile.validate()
        profile.copy(engine = "xray").validate()
        val out = profile.outbound()
        assertEquals(encryption, out["encryption"].asString)
        assertEquals("/fallback", NativeOptions.get(out, "tls.reality.spider_x")!!.asString)
        assertTrue(NativeOptions.get(out, "tls.reality.support_x25519mlkem768")!!.asBoolean)
        File("build/core-capabilities-fixtures").mkdirs()
        File("build/core-capabilities-fixtures/extended-xhttp-spider.json").writeText(SingBoxConfig.generate(profile, "127.0.0.1:21089", "test", "test").toString())
        for (path in listOf("https://other.invalid/", "//other.invalid/", "/?p=1-3", "/a#fragment")) {
            assertFalse(VpnConfig("$base&spx=${enc(path)}", "sing-box").isValid())
        }
    }
}
