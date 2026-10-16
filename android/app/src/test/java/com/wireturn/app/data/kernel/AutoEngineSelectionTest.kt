package com.wireturn.app.data.kernel

import org.junit.Assert.*
import org.junit.Test

class AutoEngineSelectionTest {
    @Test fun autoUsesCompatibleEngineInsteadOfRejectingThePreferredOne() {
        val config = VpnConfig("vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443?type=xhttp&security=tls&allowInsecure=1")
        assertEquals("sing-box", CoreCapabilities.resolve(config))
        config.validate()
    }
    @Test fun finalMaskAndNativePatchesNeverLoseRequiredSettingsInFallback() {
        val base = "vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443?security=tls"
        assertEquals(listOf("xray"), CoreCapabilities.compatibleEngines(VpnConfig("$base&fm=%7B%7D")))
        val patch = com.google.gson.JsonParser.parseString("""{"tls":{"server_name":"custom.invalid"}}""").asJsonObject
        assertEquals(listOf("sing-box"), CoreCapabilities.compatibleEngines(VpnConfig(base, options = VpnOptions(singBox = patch))))
        assertEquals("xray", CoreCapabilities.resolve(VpnConfig(base, "xray")))
    }
}
