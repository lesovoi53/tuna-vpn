package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.AwgUri
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnEditorDraft
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Synthetic panel export exercised beyond the INI parser, through file/save boundaries. */
class WgIniImportBoundaryTest {
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 251.toByte() })
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 41 })
    private val ini get() = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.8.1.3/32
        DNS = 1.1.1.1,1.0.0.1
        MTU = 1420

        **in-29386-udp - ADMIN**
        [Peer]
        PublicKey = $publicKey
        AllowedIPs = 0.0.0.0/0,::/0
        Endpoint = fixture.example:29386
    """.trimIndent()
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()

    private fun assertEndpoint(profile: Profile) {
        assertEquals("in-29386-udp - ADMIN", profile.name)
        val config = (profile.kernelConfig as KernelConfig.Vpn).config
        assertTrue(config.isValid())
        assertEquals("wireguard", config.protocol)
        val parsed = AwgUri.parse(config.uri)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), parsed.dns)
        assertEquals(privateKey, parsed.endpoint["private_key"].asString)
        assertEquals("10.8.1.3/32", parsed.endpoint.getAsJsonArray("address").single().asString)
        assertEquals(1420, parsed.endpoint["mtu"].asInt)
        val peer = parsed.endpoint.getAsJsonArray("peers").single().asJsonObject
        assertEquals(publicKey, peer["public_key"].asString)
        assertEquals("fixture.example", peer["address"].asString)
        assertEquals(29386, peer["port"].asInt)
        assertEquals(2, peer.getAsJsonArray("allowed_ips").size())
    }

    @Test fun utf8ConfFileSurvivesImportPreparationAndStoredProfileRoundTrip() {
        for (document in listOf(ini, ini.replace("\n", "\r\n"), "\uFEFF$ini")) {
            val read = readProfileText(document.toByteArray(Charsets.UTF_8).inputStream())
            val bundle = requireNotNull(parseWgProfileImport(read))
            val (prepared, skipped) = LegacyProfileImport.prepare(bundle.profiles.map { "fixture.conf" to it }, "Profile")
            assertEquals(0, skipped)
            val profile = VlessFingerprintImport.profile(prepared.single().second)
            assertEndpoint(profile)
            assertEndpoint(gson.fromJson(gson.toJson(profile), Profile::class.java).sanitize())
        }
    }

    @Test fun clipboardConfigSurvivesNameDialogPayloadEditorAndSaveValidation() {
        val config = VlessFingerprintImport.vpn(requireNotNull(VpnConfig.parse(ini.trim())))
        val restored = gson.fromJson(gson.toJson(config), VpnConfig::class.java)
        val editorUri = VpnEditorDraft.from(restored.uri).toUri()
        val saved = requireNotNull(VpnConfig.parse(editorUri))
        assertEndpoint(Profile("synthetic", saved.displayName, KernelConfig.Vpn(saved)))
        assertEquals(config.uri, saved.uri)
    }

    @Test fun clipboardIniCommitsOneNamedProfileAndReturnsItsLocalId() {
        var calls = 0
        val status = importWgIniDocument(" \n$ini\n ") { document ->
            calls++
            assertEquals(ini, document)
            val (prepared, skipped) = LegacyProfileImport.prepare(
                requireNotNull(parseWgProfileImport(document)).profiles.map { null to it }, "Profile")
            assertEquals(0, skipped)
            val saved = prepared.single().second.copy(id = "new-local-id")
            assertEndpoint(saved)
            ImportResult(added = 1, total = 1) to listOf(saved)
        }
        assertEquals("Clipboard import must commit without requiring an additional editor Save", 1, calls)
        assertEquals(ImportStatus.Success("new-local-id", ImportResult(added = 1, total = 1)), status)
    }

    @Test fun invalidIniAndUnrelatedTextCannotReachImportCommit() {
        val failCommit: (String) -> Pair<ImportResult, List<Profile>> = { error("Unexpected import") }
        assertEquals(ImportStatus.InvalidFormat, importWgIniDocument(ini.replace("MTU = 1420", "PostUp = unsafe-command"), failCommit))
        assertNull(importWgIniDocument("https://fixture.example/sub", failCommit))
    }
}
