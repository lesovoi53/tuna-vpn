package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.wireturn.app.kernel.SingBoxConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RealityHybridOptionsTest {
    private val path = "tls.reality.support_x25519mlkem768"
    private val uri = "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?type=tcp&security=reality&sni=example.com&fp=chrome&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=0123456789abcdef"

    @Test fun editorExposesHybridSettingOnlyForExtendedVless() {
        val field = CoreCapabilities.fields("sing-box", "vless").singleOrNull { it.path == path }
        assertNotNull("Reality compatibility option is missing from the editor", field)
        assertEquals("bool", field!!.kind)
        for ((engine, protocol) in listOf("xray" to "vless", "sing-box" to "hysteria2", "sing-box" to "trojan")) {
            assertFalse(CoreCapabilities.fields(engine, protocol).any { it.path == path })
        }
    }

    @Test fun defaultAndExplicitChoicesSurviveSerializationWithoutChangingRealityCredentials() {
        val original = VpnConfig(uri, "sing-box")
        val base = original.outbound()
        assertNull(NativeOptions.get(base, path))
        for (enabled in listOf(true, false)) {
            val options = VpnOptions(singBox = NativeOptions.parse("""{"tls":{"reality":{"support_x25519mlkem768":$enabled}}}"""))
            val restored = Gson().fromJson(Gson().toJson(original.copy(options = options)), VpnConfig::class.java)
            restored.validate()
            val outbound = restored.outbound()
            assertEquals(enabled, NativeOptions.get(outbound, path)!!.asBoolean)
            assertEquals(base, NativeOptions.put(outbound, path, null))
            assertEquals(options, restored.copy(engine = "xray").copy(engine = "sing-box").options)
            val config = SingBoxConfig.generate(restored, "127.0.0.1:21089", "test", "test")
            File("build/core-capabilities-fixtures").mkdirs()
            File("build/core-capabilities-fixtures/extended-reality-hybrid-$enabled.json").writeText(config.toString())
        }
    }

    @Test fun hybridSettingRequiresReality() {
        val tlsUri = uri.replace("security=reality", "security=tls")
        val options = VpnOptions(singBox = NativeOptions.parse("""{"tls":{"reality":{"support_x25519mlkem768":true}}}"""))
        assertFalse(VpnConfig(tlsUri, "sing-box", options).isValid())
    }

    @Test fun hybridSettingPreservesTheExplicitFingerprint() {
        val options = VpnOptions(singBox = NativeOptions.parse("""{"tls":{"reality":{"support_x25519mlkem768":true}}}"""))
        for (fingerprint in listOf("firefox", "chrome", "safari")) {
            val profile = VpnConfig(uri.replace("fp=chrome", "fp=$fingerprint"), "sing-box", options)
            profile.validate()
            assertEquals(fingerprint, NativeOptions.get(profile.outbound(), "tls.utls.fingerprint")!!.asString)
        }
        // An advanced fingerprint override must be considered after it is merged with the URI.
        val override = options.copy(singBox = NativeOptions.parse("""{"tls":{"utls":{"fingerprint":"chrome"},"reality":{"support_x25519mlkem768":true}}}"""))
        assertTrue(VpnConfig(uri.replace("fp=chrome", "fp=firefox"), "sing-box", override).isValid())
        assertTrue(VpnConfig(uri.replace("fp=chrome", "fp=firefox"), "sing-box").isValid())
    }
}
