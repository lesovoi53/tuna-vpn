package com.wireturn.app.data.kernel

import com.wireturn.app.kernel.XrayVpnConfig
import org.junit.Assert.*
import org.junit.Test

class XrayVpnConfigTest {
    private fun query(uri: String) = java.net.URI(uri).rawQuery.split('&').associate {
        VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('='))
    }
    private val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184) { 1 })
    private val link = "vless://00000000-0000-4000-8000-000000000001@vpn.example.com:443?type=xhttp&security=reality&pbk=test-public-key&encryption=mlkem768x25519plus.xorpub.0rtt.$key&mode=stream-up&spx=%2Ftest&extra=%7B%22seqKey%22%3A%22rq%22%7D#FP%20MSK%20vless"
    @Test fun advancedLinkKeepsAllParametersAndLocalAuth() {
        val profile = VpnConfig.parse(link)!!
        assertTrue(profile.usesXray)
        assertEquals("FP MSK vless", profile.displayName)
        val config = XrayVpnConfig.generate(profile, "127.0.0.1:2081", "local-user", "local-pass", false)
        assertEquals(link, profile.uri)
        val generated = query(config["link"].asString)
        val original = query(link)
        assertEquals(original - "extra", generated - "extra")
        val extra = com.google.gson.JsonParser.parseString(generated.getValue("extra")).asJsonObject
        assertEquals("rq", extra["seqKey"].asString)
        assertEquals("stream-up", extra["mode"].asString)
        assertEquals("local-user", config["proxy_user"].asString)
        assertFalse(config.has("local_socks5"))
        assertFalse(config.has("local_address"))
        assertThrows(IllegalArgumentException::class.java) { XrayVpnConfig.generate(profile, "0.0.0.0:2081", "u", "p", false) }
        assertThrows(IllegalArgumentException::class.java) { XrayVpnConfig.generate(profile, "127.0.0.1:2081", "", "", false) }
    }
    @Test fun rejectsAmbiguousOrMalformedAdvancedLinks() {
        // Key material is validated by packaged Xray at the import gate, not by
        // a duplicated Kotlin algorithm schema (see check_vless_import_native.py).
        assertNotNull(VpnConfig.parse(link.replace(key, key.take(350))))
        assertNull(VpnConfig.parse(link.replace("&spx=", "&type=tcp&spx=")))
        assertNull(VpnConfig.parse(link.replace("%7B%22seqKey%22%3A%22rq%22%7D", "%5B%5D")))
        assertNull(VpnConfig.parse(link.replace("&pbk=test-public-key", "")))
        assertNull(VpnConfig.parse(link.replace("stream-up", "unsupported-mode")))
    }
}
