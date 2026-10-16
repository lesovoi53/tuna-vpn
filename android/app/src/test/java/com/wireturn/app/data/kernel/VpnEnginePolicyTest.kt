package com.wireturn.app.data.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

/** Migration and import contracts; all fixtures are local and never connect to a server. */
class VpnEnginePolicyTest {
    private val uuid = "00000000-0000-4000-8000-000000000001"
    private val vless = "vless://$uuid@vpn.invalid:443?security=tls&fp=firefox"
    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun imported(uri: String): VpnConfig = requireNotNull(VpnConfig.parse(uri)) {
        "Synthetic ${uri.substringBefore(':')} fixture must import"
    }

    private fun fixtures() = listOf(
        vless to "xray",
        "trojan://p%2B%2F%3D@vpn.invalid:443?security=tls&sni=vpn.invalid" to "xray",
        "hysteria2://p%2B%2F%3D@vpn.invalid:443?sni=vpn.invalid" to "xray",
        "snell://test-password@vpn.invalid:443?version=4&reuse=true" to "sing-box",
        "ss://aes-256-gcm:test-password@vpn.invalid:443?type=tcp" to "sing-box",
        "tuic://$uuid:test-password@vpn.invalid:443?congestion_control=bbr" to "sing-box",
        "anytls://test-password@vpn.invalid:443?sni=vpn.invalid" to "sing-box",
        "mierus://test:test-password@vpn.invalid/?profile=Test&port=20000&protocol=TCP" to "mieru"
    )

    @Test fun supportedImportedProtocolsHaveAnAssignedCoreAndRemainValid() {
        for ((uri, expected) in fixtures()) {
            val parsed = imported(uri)
            val assigned = VpnEnginePolicy.apply(parsed)
            assertEquals(parsed.protocol, expected, VpnEnginePolicy.assignedEngine(assigned))
            assertEquals(parsed.uri, assigned.uri)
            assigned.validate()
        }
    }

    @Test fun oldExplicitSelectionWithoutNativeSettingsMigratesIdempotently() {
        for ((uri, expected) in fixtures().filter { it.second == "xray" }) {
            val old = imported(uri).copy(engine = "sing-box")
            val migrated = VpnEnginePolicy.apply(old)
            assertEquals(expected, migrated.selectedEngine)
            assertEquals(old.uri, migrated.uri)
            assertEquals(old.options, migrated.options)
            assertEquals(migrated, VpnEnginePolicy.apply(migrated))
            assertEquals(old.copy(engine = "xray"), migrated)
        }
    }

    @Test fun emptyNativeSettingsDoNotPinTheOldCore() {
        val old = VpnConfig(vless, "sing-box", VpnOptions(singBox = JsonObject(), xray = JsonObject()))
        val migrated = VpnEnginePolicy.apply(old)
        assertEquals("xray", migrated.selectedEngine)
        assertEquals(old.options, migrated.options)
    }

    @Test fun anExistingSingleCorePatchIsNeverDroppedDuringAssignment() {
        for (engine in listOf("sing-box", "xray")) {
            val patch = if (engine == "sing-box") json("""{"tls":{"server_name":"custom.invalid"}}""")
                else json("""{"streamSettings":{"tlsSettings":{"serverName":"custom.invalid"}}}""")
            val options = VpnOptions().withEngine(engine, patch)
            val before = options.copy(singBox = options.singBox?.deepCopy(), xray = options.xray?.deepCopy())
            val old = VpnConfig(vless, "auto", options)
            val assigned = VpnEnginePolicy.apply(old)
            assertEquals(engine, assigned.selectedEngine)
            assertEquals(before, assigned.options)
            assertEquals(before, old.options)
            assertEquals(old.uri, assigned.uri)
            assigned.validate()
            assertEquals(assigned, VpnEnginePolicy.apply(assigned))
        }
    }

    @Test fun explicitLegacySelectionWithBothNativePatchesPreservesItsActiveSettings() {
        val options = dualOptions()
        for (engine in listOf("xray", "sing-box")) {
            val old = VpnConfig(vless, engine, options)
            val assigned = VpnEnginePolicy.apply(old)
            assertEquals(old, assigned)
            assigned.validate()
        }
    }

    @Test fun legacyAutoWithBothPatchesKeepsItsPreviouslyPreferredCore() {
        val options = dualOptions()
        val plain = VpnEnginePolicy.apply(VpnConfig(vless, "auto", options))
        assertEquals("sing-box", plain.selectedEngine)
        assertEquals(options, plain.options)
        val requiringXray = VpnEnginePolicy.apply(VpnConfig("$vless&fm=%7B%7D", "auto", options))
        assertEquals("xray", requiringXray.selectedEngine)
        assertEquals(options, requiringXray.options)
        assertEquals(requiringXray, VpnEnginePolicy.apply(requiringXray))
    }

    @Test fun hysteriaAdapterExceptionsSelectCompatibleCoreWithoutDroppingParameters() {
        val base = "hysteria2://test-password@vpn.invalid:443?sni=vpn.invalid"
        for (suffix in listOf("&insecure=1", "&server_ports=443,444&hop_interval=0.5")) {
            val parsed = imported(base + suffix)
            val assigned = VpnEnginePolicy.apply(parsed)
            assertEquals("sing-box", assigned.selectedEngine)
            assertEquals(parsed.uri, assigned.uri)
            assigned.validate()
            assertNotNull(VpnEnginePolicy.note(assigned))
        }
    }

    @Test fun xhttpExtraAndEncodedCredentialsArePreservedDuringMigration() {
        val extra = """{"xPaddingBytes":"50-150","scMaxEachPostBytes":"3000000","uplinkHTTPMethod":"POST","sessionIDKey":"auth%2F+","xmux":{"maxConcurrency":"1"}}"""
        val xhttp = "$vless&type=xhttp&mode=packet-up&path=%2Fstatic%2Fsegment.ts&extra=${encode(extra)}#Keep%20name"
        val trojan = "trojan://p%2B%2F%3D%252F%3A%40@vpn.invalid:443?security=tls&sni=vpn.invalid#Keep%2Bname"
        for (source in listOf(xhttp, trojan)) {
            val parsed = imported(source)
            assertEquals(source, parsed.uri)
            val migrated = VpnEnginePolicy.apply(parsed.copy(engine = "sing-box"))
            assertEquals("xray", migrated.selectedEngine)
            assertEquals(source, migrated.uri)
            migrated.validate()
        }
    }

    @Test fun profileAndClientMigrationUseTheSamePolicyWithoutChangingIdentityOrCredentials() {
        for (original in listOf(VpnConfig(vless, "sing-box"), VpnConfig(vless, "auto", dualOptions()))) {
            val expected = VpnEnginePolicy.apply(original)
            val profile = Profile("stable-id", "Local name", KernelConfig.Vpn(original),
                subscriptionId = "subscription-id", subscriptionSourceId = "remote-id")
            val migratedProfile = profile.sanitize()
            val client = ClientConfig(kernelConfig = KernelConfig.Vpn(original), socksUser = "local-user", socksPass = "local-password")
            val migratedClient = client.fillDefaults()
            assertEquals(KernelConfig.Vpn(expected), migratedProfile.kernelConfig)
            assertEquals(KernelConfig.Vpn(expected), migratedClient.kernelConfig)
            assertEquals(profile.id, migratedProfile.id)
            assertEquals(profile.name, migratedProfile.name)
            assertEquals(profile.subscriptionId, migratedProfile.subscriptionId)
            assertEquals(profile.subscriptionSourceId, migratedProfile.subscriptionSourceId)
            assertEquals(client.socksUser, migratedClient.socksUser)
            assertEquals(client.socksPass, migratedClient.socksPass)
            assertEquals(migratedProfile, migratedProfile.sanitize())
            assertEquals(migratedClient, migratedClient.fillDefaults())
        }
    }

    @Test fun incompleteEditorInputDoesNotCrashAssignmentOrRewriteText() {
        for (uri in listOf("", "vless:", "vless://", "vless://%ZZ@", "hysteria2://test@", "not a URI")) {
            val original = VpnConfig(uri, "auto")
            val assigned = VpnEnginePolicy.apply(original)
            assertEquals(uri, assigned.uri)
            assertEquals(original.options, assigned.options)
            assertEquals(assigned, VpnEnginePolicy.apply(assigned))
            VpnEnginePolicy.note(assigned)
        }
    }

    private fun dualOptions() = VpnOptions(
        singBox = json("""{"tls":{"server_name":"sb.invalid"}}"""),
        xray = json("""{"streamSettings":{"tlsSettings":{"serverName":"xray.invalid"}}}""")
    )
}
