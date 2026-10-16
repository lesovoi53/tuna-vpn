package com.wireturn.app.kernel

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnOptions
import org.junit.Assert.*
import org.junit.Test

class SnellTfoCompatibilityTest {
    private val uri = "snell://fixture-secret@192.0.2.1:19552/?version=5&reuse=true&tfo=true&obfs-mode=http&obfs-host=example.com#Snell"
    private fun outbound(profile: VpnConfig) = SingBoxConfig.generate(profile, "127.0.0.1:1080", "qa", "qa")
        .getAsJsonArray("outbounds")[0].asJsonObject

    @Test fun oldAndImportedProfilesUseOrdinaryTcpWithoutRewritingTheirSource() {
        val original = VpnConfig.parse(uri)!!
        assertFalse(outbound(original)["tcp_fast_open"].asBoolean)
        assertEquals(uri, original.uri)
        assertTrue(original.outbound()["tcp_fast_open"].asBoolean)
        assertTrue(outbound(original)["reuse"].asBoolean)
        assertEquals("http", outbound(original)["obfs_mode"].asString)
    }

    @Test fun nativeOverrideCannotSilentlyBypassCompatibility() {
        val patch = JsonObject().apply { addProperty("tcp_fast_open", true) }
        val profile = VpnConfig(uri.replace("tfo=true", "tfo=false"), "sing-box", VpnOptions(singBox = patch))
        assertFalse(outbound(profile)["tcp_fast_open"].asBoolean)
        assertTrue(patch["tcp_fast_open"].asBoolean)
    }

    @Test fun explicitOptInHonoursRequestedTfoButDoesNotEnableAnUnrequestedSetting() {
        val enabled = VpnConfig(uri, "sing-box", VpnOptions(allowExperimentalSnellTfo = true))
        assertTrue(outbound(enabled)["tcp_fast_open"].asBoolean)
        assertFalse(outbound(enabled.copy(uri = uri.replace("tfo=true", "tfo=false")))["tcp_fast_open"].asBoolean)
        assertFalse(SingBoxConfig.snellTfoSuppressed(enabled))
        assertTrue(SingBoxConfig.snellTfoSuppressed(enabled.copy(options = null)))
    }

    @Test fun oldJsonUsesCompatibilityAndOptInSurvivesSerializationAndAdvancedEdits() {
        val gson = Gson()
        assertFalse(gson.fromJson("{\"version\":1}", VpnOptions::class.java).allowExperimentalSnellTfo)
        val changed = VpnOptions(allowExperimentalSnellTfo = true).withEngine("sing-box", JsonObject())
        val profile = VpnConfig(uri, "sing-box", changed)
        val restored = gson.fromJson(gson.toJson(profile), VpnConfig::class.java)
        assertEquals(profile, restored)
        assertTrue(outbound(restored)["tcp_fast_open"].asBoolean)
        // A subscription refresh with the original URI returns to the default runtime policy.
        assertFalse(outbound(VpnConfig.parse(uri)!!)["tcp_fast_open"].asBoolean)
    }

    @Test fun compatibilityDoesNotChangeOtherTransportSettingsOrOtherProtocols() {
        val profile = VpnConfig(uri, "sing-box", VpnOptions(singBox = JsonObject().apply {
            addProperty("tcp_keep_alive", "30s")
        }))
        val expected = profile.outbound().apply { addProperty("tcp_fast_open", false) }
        assertEquals(expected, outbound(profile))
        val trojan = VpnConfig("trojan://fixture@192.0.2.1:443?security=tls", "sing-box",
            VpnOptions(singBox = JsonObject().apply { addProperty("tcp_fast_open", true) }))
        assertEquals(trojan.outbound(), outbound(trojan))
    }
}
