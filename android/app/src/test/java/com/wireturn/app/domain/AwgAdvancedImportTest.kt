package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ProfileListEdit
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.wireturn.app.data.kernel.AwgUri
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnEditorDraft
import com.wireturn.app.kernel.SingBoxConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** The clipboard and .conf entry points must preserve the whole extended AWG document. */
class AwgAdvancedImportTest {
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 11 })
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 22 })
    private val protectionKey = Base64.getEncoder().encodeToString(ByteArray(32) { 33 })
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun packet(bytes: Int) = "<b 0x${"a5".repeat(bytes)}><r 32><b 0x0303><r 32>"
    private fun ini(bytes: Int) = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.8.1.2/32
        DNS = 8.8.8.8,8.8.4.4
        MTU = 1416
        Jc = 3
        Jmin = 56
        Jmax = 209
        S1 = 84
        S2 = 30
        S3 = 25
        S4 = 24
        H1 = 4294967292
        H2 = 4294967293
        H3 = 4294967294
        H4 = 4294967295
        ${(1..5).joinToString("\n") { "I$it = " + packet(bytes).replace("<", "\\<") }}
        HeaderProtectionKey = $protectionKey
        ContentPaddingAddition = 17-106
        RekeyAfterTime = 114-140
        RekeyTimeout = 3-4
        RejectAfterTime = 173-190
        KeepaliveTimeout = 8-15
        MaxHandshakeAttempts = 14-24
        RandomTrailers = on
        DisableCookies = on

        **in-17156-udp - ADMIN**
        [Peer]
        PublicKey = $publicKey
        AllowedIPs = 0.0.0.0/0,::/0
        Endpoint = example.com:17156
    """.trimIndent()

    private fun assertProfile(profile: Profile, bytes: Int) {
        assertEquals("in-17156-udp - ADMIN", profile.name)
        val parsed = AwgUri.parse((profile.kernelConfig as KernelConfig.Vpn).config.uri)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), parsed.dns)
        assertEquals(privateKey, parsed.endpoint["private_key"].asString)
        assertEquals(1416, parsed.endpoint["mtu"].asInt)
        val awg = parsed.endpoint.getAsJsonObject("amnezia")
        assertEquals(AwgUri.amneziaFields, awg.keySet())
        (1..5).forEach { assertEquals(packet(bytes), awg["i$it"].asString) }
        assertEquals(protectionKey, awg["header_protection_key"].asString)
        assertEquals("4294967295", awg["h4"].asString)
        mapOf("content_padding_addition" to "17-106", "rekey_after_time" to "114-140",
            "rekey_timeout" to "3-4", "reject_after_time" to "173-190",
            "keepalive_timeout" to "8-15", "max_handshake_attempts" to "14-24"
        ).forEach { (field, value) -> assertEquals(value, awg[field].asString) }
        assertTrue(awg["random_trailers"].asBoolean)
        assertTrue(awg["disable_cookies"].asBoolean)
        val peer = parsed.endpoint.getAsJsonArray("peers").single().asJsonObject
        assertEquals(publicKey, peer["public_key"].asString)
        assertEquals("example.com", peer["address"].asString)
        assertEquals(17156, peer["port"].asInt)
    }

    private fun exerciseImports(bytes: Int) {
        val text = ini(bytes)
        val fromFile = ProfileFileImport.parse(readProfileText(text.byteInputStream()), gson)
        assertEquals(0, fromFile.skippedEntries)
        assertProfile(fromFile.profiles.single(), bytes)
        var commits = 0
        val status = importWgIniDocument(text) { document ->
            val bundle = ProfileFileImport.parse(document, gson)
            val (prepared, skipped) = LegacyProfileImport.prepare(bundle.profiles.map { null to it }, "Profile")
            assertEquals(0, skipped)
            assertProfile(prepared.single().second, bytes)
            commits++
            ImportResult(added = 1, total = 1) to prepared.map { it.second }
        }
        assertTrue("Pasted AWG must reach a successful import", status is ImportStatus.Success)
        assertEquals(1, commits)
        assertProfile(gson.fromJson(gson.toJson(fromFile.profiles.single()), Profile::class.java).sanitize(), bytes)
        val parsed = requireNotNull(VpnConfig.parse(text))
        val editorSaved = requireNotNull(VpnConfig.parse(VpnEditorDraft.from(parsed.uri).toUri()))
        assertEquals(parsed.uri, editorSaved.uri)
        assertProfile(Profile("synthetic", editorSaved.displayName, KernelConfig.Vpn(editorSaved)), bytes)
    }

    @Test fun completeExtendedAwgImportsFromClipboardAndConf() {
        exerciseImports(350)
        val text = ini(350)
        val output = java.io.File("build/awg-import-fixtures").apply { mkdirs() }
        java.io.File(output, "synthetic-extended.conf").writeText(text)
        java.io.File(output, "synthetic-extended.json").writeText(
            SingBoxConfig.generate(requireNotNull(VpnConfig.parse(text)), "127.0.0.1:21089", "validation", "validation").toString())
    }

    @Test fun unsupportedFieldCannotReachClipboardCommitOrPartiallyImportFile() {
        val text = ini(350).replace("RandomTrailers = on", "UnknownAwgOption = on")
        assertEquals(ImportStatus.InvalidFormat, importWgIniDocument(text) { error("Unexpected import commit") })
        assertThrows(IllegalArgumentException::class.java) { ProfileFileImport.parse(text, gson) }
    }

    @Test fun panelTlsTemplatesPreserveEveryFieldAcrossRealImportEntries() {
        // Public packet masks, synthetic keys and example.com; never a live account.
        val text = requireNotNull(javaClass.getResource("/awg/extended-panel-synthetic.conf")).readText()
        val file = ProfileFileImport.parse(readProfileText(text.byteInputStream()), gson).profiles.single()
        val config = (file.kernelConfig as KernelConfig.Vpn).config
        assertEquals("in-17156-udp - ADMIN", file.name)
        assertEquals("awg", config.protocol)
        val amnezia = config.outbound().getAsJsonObject("amnezia")
        assertEquals(AwgUri.amneziaFields, amnezia.keySet())
        text.lineSequence().filter { it.matches(Regex("I[1-5] = .*")) }.forEach { line ->
            val field = line.substringBefore('=').trim().lowercase()
            assertEquals(line.substringAfter('=').trim().replace("\\<", "<"), amnezia[field].asString)
        }
        val status = importWgIniDocument(text) { document ->
            val parsed = ProfileFileImport.parse(document, gson)
            val (prepared, skipped) = LegacyProfileImport.prepare(parsed.profiles.map { null to it }, "Profile")
            assertEquals(0, skipped)
            assertEquals(file, prepared.single().second)
            ImportResult(added = 1, total = 1) to prepared.map { it.second }
        }
        assertTrue(status is ImportStatus.Success)
        assertEquals(config, VpnConfig.parse(text))
        assertEquals(config.uri, VpnEditorDraft.from(config.uri).toUri())
        assertEquals(file, gson.fromJson(gson.toJson(file), Profile::class.java).sanitize())
        val output = java.io.File("build/awg-import-fixtures").apply { mkdirs() }
        java.io.File(output, "panel-synthetic.json").writeText(
            SingBoxConfig.generate(config, "127.0.0.1:21089", "validation", "validation").toString())
    }

    @Test fun separatelyImportedAccountCanCoexistWithSubscriptionOwnedAccount() {
        val text = requireNotNull(javaClass.getResource("/awg/extended-panel-synthetic.conf")).readText()
        val incoming = ProfileFileImport.parse(text, gson).profiles.single()
        val owned = incoming.copy(id = "subscription-local-id", subscriptionId = "synthetic-sub",
            subscriptionSourceId = incoming.id)
        // ProfileManager assigns a fresh local ID when subscriptionId is null. Its
        // AWG dedup input is this import batch, not the already stored profiles.
        val local = AwgProfileDedup.filter(listOf(incoming), { it }).single().copy(id = "new-local-id")
        assertEquals(AwgProfileDedup.key(owned), AwgProfileDedup.key(local))
        val preferences = mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(listOf(owned)))
        val saved = ProfileListEdit.AppendMany(listOf(local)).commit(preferences, gson)
        assertEquals(listOf(owned, local), saved)
        assertEquals("synthetic-sub", saved.first().subscriptionId)
        assertNull(saved.last().subscriptionId)
        val reveal = requireNotNull(ProfileReveal.resolve(local.id, saved, setOf("synthetic-sub"),
            emptySet(), com.wireturn.app.data.ConnectionCategory.VPN, false))
        assertFalse("The separately imported copy is in the standalone section", reveal.subscriptions)
        assertEquals(local.id, reveal.targetId)
    }
}
