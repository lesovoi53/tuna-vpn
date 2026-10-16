package com.wireturn.app.data.kernel

import com.wireturn.app.kernel.XrayVpnConfig
import org.junit.Assert.*
import org.junit.Test

class VlessNativeImportContractTest {
    private val base = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443?type=xhttp&security=reality&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sni=example.invalid&fp=firefox"

    @Test fun realityCompatibilityHintDoesNotBlockXrayImportOrChangeEncryption() {
        for (flag in listOf("true", "false", "1", "0")) {
            val uri = "$base&encryption=none&support-x25519mlkem768=$flag"
            val profile = requireNotNull(VpnConfig.parse(uri)) { "Import rejected compatibility hint $flag" }
            assertEquals("xray", VpnEnginePolicy.assignedEngine(profile))
            assertEquals(uri, profile.uri)
            val generated = XrayVpnConfig.generate(profile, "127.0.0.1:21089", "u", "p", false)
            assertTrue(generated["link"].asString.contains("encryption=none"))
        }
        assertNull(VpnConfig.parse("$base&support-x25519mlkem768=maybe"))
    }

    @Test fun encryptionSchemaBelongsToNativeCoreNotKotlinAlgorithmAllowlist() {
        // A future algorithm is syntactically valid, NOT declared supported: the native
        // import gate must reject it using the actual packaged Xray before it is saved.
        val uri = "$base&encryption=future-test-algorithm.key"
        val profile = VpnConfig(uri, "xray")
        profile.validate()
        assertEquals(uri, XrayVpnConfig.generate(profile, "127.0.0.1:21089", "u", "p", false)["link"].asString)
    }

    @Test fun writeNativeAcceptanceFixtures() {
        val directory = java.io.File("build/vless-import-native-fixtures").apply { mkdirs() }
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184))
        val cases = linkedMapOf("pass-none" to "none", "reject-unknown" to "future-test-algorithm.key",
            "reject-short-key" to "mlkem768x25519plus.xorpub.0rtt.${key.take(350)}")
        for (mode in listOf("native", "xorpub", "random")) for (rtt in listOf("0rtt", "1rtt"))
            cases["pass-$mode-$rtt"] = "mlkem768x25519plus.$mode.$rtt.$key"
        for ((name, encryption) in cases) {
            val profile = VpnConfig("$base&encryption=$encryption&support-x25519mlkem768=true", "xray")
            java.io.File(directory, "$name.json").writeText(
                XrayVpnConfig.generate(profile, "127.0.0.1:21089", "u", "p", false).toString())
        }
    }
}
