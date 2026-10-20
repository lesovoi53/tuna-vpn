package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.AwgUri
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnEditorDraft
import com.wireturn.app.kernel.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class AwgKeepaliveImportTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun key(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { seed.toByte() })
    private fun document(keepalive: String = "20-35") = """
        [Interface]
        PrivateKey = ${key(11)}
        Address = 10.8.1.2/32
        Jc = 3
        Jmin = 56
        Jmax = 209
        H1 = 100-110
        H2 = 200-210
        H3 = 300-310
        H4 = 400-410
        [Peer]
        PublicKey = ${key(22)}
        PresharedKey = ${key(33)}
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = example.com:17156
        PersistentKeepalive = $keepalive
    """.trimIndent()

    @Test fun rangeKeepaliveSurvivesConfImport() {
        val bundle = parseWgProfileImport(readProfileText(document().byteInputStream()))
        assertNotNull("AWG with a range keepalive must reach the file import boundary", bundle)
        val config = (bundle!!.profiles.single().kernelConfig as KernelConfig.Vpn).config
        val peer = AwgUri.parse(config.uri).endpoint.getAsJsonArray("peers").single().asJsonObject
        assertEquals("20-35", peer["persistent_keepalive_interval"].asString)
        assertEquals(key(33), peer["pre_shared_key"].asString)
        assertEquals("100-110", config.outbound().getAsJsonObject("amnezia")["h1"].asString)
        val output = File("build/awg-import-fixtures").apply { mkdirs() }
        File(output, "range-keepalive-synthetic.json").writeText(
            SingBoxConfig.generate(config, "127.0.0.1:21089", "validation", "validation").toString())
    }

    @Test fun fileClipboardStorageAndEditorKeepTheWholeRange() {
        val text = document()
        val imported = ProfileFileImport.parse(readProfileText(text.byteInputStream()), gson).profiles.single()
        val restored = gson.fromJson(gson.toJson(imported), Profile::class.java).sanitize()
        assertEquals(imported, restored)
        val config = (restored.kernelConfig as KernelConfig.Vpn).config
        assertEquals(config, VpnConfig.parse(VpnEditorDraft.from(config.uri).toUri()))
        val status = importWgIniDocument(text) { value ->
            val profiles = ProfileFileImport.parse(value, gson).profiles
            ImportResult(added = 1, total = 1) to profiles
        }
        assertTrue(status is ImportStatus.Success)
        val keepalive = VpnEditorDraft.from(config.uri).fields.single { it.queryName == "persistent_keepalive_interval" }
        val changed = VpnEditorDraft.from(config.uri).change(keepalive.key, "30-45").toUri()
        assertEquals("30-45", AwgUri.parse(changed).endpoint.getAsJsonArray("peers").single().asJsonObject["persistent_keepalive_interval"].asString)
    }

    @Test fun multiPeerRangesSurviveCanonicalUriAndPrimaryEditing() {
        val text = document() + "\n" + """
            [Peer]
            PublicKey = ${key(44)}
            AllowedIPs = 192.0.2.0/24
            Endpoint = example.invalid:17157
            PersistentKeepalive = 40-55
        """.trimIndent()
        val config = requireNotNull(VpnConfig.parse(text))
        val draft = VpnEditorDraft.from(config.uri)
        val field = draft.fields.single { it.queryName == "persistent_keepalive_interval" }
        val peers = AwgUri.parse(draft.change(field.key, "30-45").toUri()).endpoint.getAsJsonArray("peers")
        assertEquals(listOf("30-45", "40-55"), peers.map { it.asJsonObject["persistent_keepalive_interval"].asString })
    }

    @Test fun scalarAndEqualBoundsStayNumericAndDisabledStaysZero() {
        for (value in listOf("0", "25", "65535", "25-25")) {
            val peer = requireNotNull(VpnConfig.parse(document(value))).outbound().getAsJsonArray("peers").single().asJsonObject
            assertTrue(peer["persistent_keepalive_interval"].asJsonPrimitive.isNumber)
            assertEquals(value.substringBefore('-').toInt(), peer["persistent_keepalive_interval"].asInt)
        }
        assertNotNull(VpnConfig.parse(document("0-65535")))
    }

    @Test fun malformedReversedAndOverflowIntervalsCannotPartiallyImport() {
        for (value in listOf("-1", "65536", "35-20", "0-65536", "65536-65537", "1-2-3", "1.5", "+25", "20-")) {
            assertNull(value, VpnConfig.parse(document(value)))
            assertEquals(ImportStatus.InvalidFormat, importWgIniDocument(document(value)) { error("Unexpected commit") })
        }
    }

    @Test fun optionalPrivateConfImport() {
        val paths = System.getenv("TUNA_PRIVATE_AWG_FILES") ?: System.getenv("TUNA_PRIVATE_AWG_FILE")
        val documents = if (paths == null) listOf(document()) else paths.split(File.pathSeparator).map { path ->
            require(path.isNotBlank()) { "Private fixture path is empty" }
            File(path).inputStream().use(::readProfileText)
        }
        for (document in documents) {
            // Never print input, parser exception messages, keys or endpoint addresses.
            val success = runCatching {
                val profile = ProfileFileImport.parse(document, gson).profiles.single()
                val restored = gson.fromJson(gson.toJson(profile), Profile::class.java).sanitize()
                val config = (restored.kernelConfig as KernelConfig.Vpn).config
                profile == restored && VpnConfig.parse(VpnEditorDraft.from(config.uri).toUri()) == config
            }.getOrDefault(false)
            assertTrue("Private AWG document must survive import/save/editor round trip", success)
        }
    }
}
