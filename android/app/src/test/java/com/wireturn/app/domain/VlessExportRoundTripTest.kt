package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SafeEnumTypeAdapterFactory
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.kernel.XrayVpnConfig
import java.net.URI
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class VlessExportRoundTripTest {
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()

    @Test fun profileExportImportPreservesEncryptionAndRealityHintWhileRuntimeRemovesOnlyHint() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184) { (it % 251).toByte() })
        val encryptions = listOf("none") + listOf("native", "xorpub", "random").flatMap { mode ->
            listOf("0rtt", "1rtt").map { rtt -> "mlkem768x25519plus.$mode.$rtt.$key" }
        }
        val base = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443?type=tcp&security=reality&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sni=example.invalid&fp=firefox"
        for (encryption in encryptions) for (flag in listOf("true", "false", "1", "0")) {
            val uri = "$base&encryption=$encryption&support-x25519mlkem768=$flag#Export%20fixture"
            val original = Profile(id = "roundtrip-id", name = "Export fixture",
                kernelConfig = KernelConfig.Vpn(requireNotNull(VpnConfig.parse(uri))))
            // Both exported JSON shapes use ProfileManager's actual Gson adapters:
            // one profile file and the selected-profiles JSON array.
            for (json in listOf(gson.toJson(original), gson.toJson(listOf(original)))) {
                val decoded = requireNotNull(ProfileEncoder.decode(ProfileEncoder.encodeForSharing(json)!!))
                val bundle = ProfileFileImport.parse(decoded, gson)
                assertEquals(0, bundle.skippedEntries)
                val restored = bundle.profiles.single()
                assertEquals(original.id, restored.id)
                assertEquals(original.name, restored.name)
                val vpn = (restored.kernelConfig as KernelConfig.Vpn).config
                assertEquals(uri, vpn.uri)
                val runtime = XrayVpnConfig.generate(vpn, "127.0.0.1:21089", "fixture", "fixture", false)["link"].asString
                assertEquals(parameters(uri) - "support-x25519mlkem768", parameters(runtime))
                assertEquals(encryption, parameters(runtime)["encryption"])
                assertFalse(parameters(runtime).containsKey("support-x25519mlkem768"))
                assertEquals(uri, vpn.uri) // Execution must not mutate exported profile data.
            }
        }
    }

    private fun parameters(uri: String) = URI(uri).rawQuery.split('&').associate {
        VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('='))
    }
}
