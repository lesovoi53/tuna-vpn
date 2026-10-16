package com.wireturn.app.data.kernel

import com.google.gson.JsonParser
import com.wireturn.app.kernel.XrayVpnConfig
import org.junit.Assert.*
import org.junit.Test

class VpnFragmentationTest {
    private val profile = VpnConfig("vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443?type=ws&security=tls", "xray")

    @Test fun threeModesMapToDistinctNativeAlgorithms() {
        for (mode in listOf("tls", "tcp", "both")) {
            val source = profile.copy(options = VpnOptions(fragmentation = VpnFragmentation(mode, "3-3", "0")))
            val patch = XrayVpnConfig.generate(source, "127.0.0.1:1080", "u", "p", false).getAsJsonObject("outbound_patch")
            val mask = NativeOptions.get(patch, "streamSettings.finalmask.tcp")!!.asJsonArray[0].asJsonObject
            val settings = mask.getAsJsonObject("settings")
            assertEquals(if (mode == "tcp") "1" else "tlshello", settings["packets"].asString)
            assertEquals("3-3", settings["length"].asString)
            if (mode == "both") assertEquals(2, settings.getAsJsonArray("delays").size()) else assertEquals("0", settings["delay"].asString)
        }
    }

    @Test fun defaultsDoNotOverwriteProfileLinkNativeOrIncompatibleProfiles() {
        val defaults = VpnFragmentation("both")
        val inherited = VpnFragmentation.applyDefault(profile, defaults)
        assertEquals(defaults, inherited.options!!.fragmentation)
        assertNull(profile.options)
        val off = profile.copy(options = VpnOptions(fragmentation = VpnFragmentation()))
        assertEquals(off, VpnFragmentation.applyDefault(off, defaults))
        val uri = profile.copy(uri = profile.uri + "&fragment=3-3%2C0-1%2Ctlshello")
        assertEquals(uri, VpnFragmentation.applyDefault(uri, defaults))
        val plain = profile.copy(uri = profile.uri.replace("security=tls", "security=none"))
        assertEquals(plain, VpnFragmentation.applyDefault(plain, defaults))
        val raw = profile.copy(options = VpnOptions(xray = JsonParser.parseString("""{"streamSettings":{"finalmask":{"tcp":[]}}}""").asJsonObject))
        assertEquals(raw, VpnFragmentation.applyDefault(raw, defaults))
    }

    @Test fun profileOffOverridesUriWithoutDeletingOtherMasks() {
        val fm = java.net.URLEncoder.encode("""{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"10","delay":"0"}},{"type":"header-custom","settings":{}}]}""", "UTF-8")
        val source = profile.copy(uri = profile.uri + "&fm=$fm", options = VpnOptions(fragmentation = VpnFragmentation()))
        val output = XrayVpnConfig.generate(source, "127.0.0.1:1080", "u", "p", false)
        val tcp = NativeOptions.get(output.getAsJsonObject("outbound_patch"), "streamSettings.finalmask.tcp")!!.asJsonArray
        assertEquals(1, tcp.size())
        assertEquals("header-custom", tcp[0].asJsonObject["type"].asString)
    }

    @Test fun invalidAndContradictoryFragmentSettingsFailClosed() {
        for (value in listOf(VpnFragmentation("broken"), VpnFragmentation("tls", "0"), VpnFragmentation("tcp", "5-1"), VpnFragmentation("both", interval = "-1"))) {
            assertThrows(IllegalArgumentException::class.java) { value.validate() }
        }
        val enabled = VpnOptions(fragmentation = VpnFragmentation("tls"))
        assertThrows(IllegalArgumentException::class.java) { profile.copy(uri = profile.uri.replace("security=tls", "security=none"), options = enabled).validate() }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(engine = "sing-box", options = enabled).validate() }
    }
}
