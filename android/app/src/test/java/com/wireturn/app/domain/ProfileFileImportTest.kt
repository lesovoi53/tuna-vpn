package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.SafeEnumTypeAdapterFactory
import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ProfileFileImportTest {
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun profileUri(): String = "snell://fixture-secret@192.0.2.5:443?version=5#First"


    @Test fun uriLinesKeepValidNeighborsAndReportRejectedEntries() {
        val parsed = ProfileFileImport.parse("${profileUri()}\r\n\r\nunknown-bundle://invalid\n" +
            "naive+https://fixture:password@fixture.example:443#Second\nunknown://fixture.example", gson)
        assertEquals(2, parsed.profiles.size)
        assertTrue(parsed.profiles.first().kernelConfig is KernelConfig.Vpn)
        assertTrue(parsed.profiles.last().kernelConfig is KernelConfig.Vpn)
        assertEquals(2, parsed.skippedEntries)
        assertEquals(2, parsed.importIssues.orEmpty().size)
    }

    @Test fun duplicateUriLinesReachExistingDuplicatePolicyUnchanged() {
        val uri = profileUri()
        val profiles = ProfileFileImport.parse("$uri\n$uri", gson).profiles
        assertEquals(2, profiles.size)
        assertEquals(profiles.first(), profiles.last())
    }

    @Test fun jsonKeepsIdsLocalOptionsAndMalformedJsonNeverFallsBackToUriScan() {
        val uri = profileUri()
        val wrapped = JsonObject().apply {
            addProperty("id", "existing-id")
            addProperty("name", "Local title")
            addProperty("uri", uri)
            addProperty("keepSubscriptionAddress", true)
        }.toString()
        assertEquals(LegacyProfileImport.parse(wrapped, gson), ProfileFileImport.parse(wrapped, gson))
        val profile = ProfileFileImport.parse("[$wrapped]", gson).profiles.single()
        assertEquals("existing-id", profile.id)
        assertEquals("Local title", profile.name)
        assertTrue(profile.keepSubscriptionAddress)
        assertThrows(RuntimeException::class.java) { ProfileFileImport.parse("[$wrapped", gson) }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileFileImport.parse("""{"schema":"tuna.subscription","profiles":[$wrapped]}""", gson)
        }
    }

    @Test fun plainSubscriptionUrlDoesNotFetchOrBecomeAProfile() {
        val result = ProfileFileImport.parse("https://fixture.example/subscription", gson)
        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.skippedEntries)
    }

    @Test fun wgDocumentAndUriListsKeepExistingAccountIdentity() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 41 })
        val ini = """
            [Interface]
            PrivateKey = $key
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $key
            AllowedIPs = 0.0.0.0/0
            Endpoint = fixture.example:51820
        """.trimIndent()
        assertEquals(parseWgProfileImport(ini), ProfileFileImport.parse("\uFEFF$ini", gson))
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        val wg = "wg://fixture.example:51820?private_key=$encodedKey&public_key=$encodedKey&local_address=10.0.0.2%2F32"
        val parsed = ProfileFileImport.parse("$wg\n${profileUri()}", gson)
        assertEquals(0, parsed.skippedEntries)
        assertEquals(2, parsed.profiles.size)
        assertEquals(parseWgProfileImport(wg)!!.profiles.single(), parsed.profiles.first())
    }

    @Test fun exportedTunaEnvelopeAndLegacyAliasesImportFromTextFiles() {
        val original = LegacyProfileImport.parse("""{"id":"shared-id","name":"Shared title","uri":"${profileUri()}","keepSubscriptionAddress":true}""", gson).profiles.single()
        // The share UI encodes getProfilesJson(), which is an array of complete profiles.
        val exported = gson.toJson(listOf(original))
        val encoded = ProfileEncoder.encodeForSharing(exported)!!
        for (scheme in listOf("tuna", "wireturn", "wt", "TUNA")) {
            val imported = ProfileFileImport.parse("$scheme://$encoded", gson)
            assertEquals(listOf(original), imported.profiles)
            assertEquals(0, imported.skippedEntries)
        }
    }

    @Test fun malformedOrUnsupportedEnvelopesDoNotHideValidFileNeighbors() {
        val valid = "tuna://" + ProfileEncoder.encode("""{"name":"Shared","uri":"${profileUri()}"}""")
        val versioned = "tuna://" + ProfileEncoder.encode("""{"schema":"future.schema","profiles":[]}""")
        val result = ProfileFileImport.parse("tuna://invalid\n$valid\n$versioned\n${profileUri()}", gson)
        assertEquals(2, result.profiles.size)
        assertEquals(2, result.skippedEntries)
        assertEquals(2, result.importIssues.orEmpty().size)
    }

    @Test fun compressedFileEnvelopeCannotBypassTheExpandedSizeLimit() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(bytes).use { output ->
            val chunk = ByteArray(8192) { ' '.code.toByte() }
            repeat(ProfileEncoder.MAX_DECODED_BYTES / chunk.size + 1) { output.write(chunk) }
        }
        val link = "wt://" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
        val parsed = ProfileFileImport.parse(link, gson)
        assertTrue(parsed.profiles.isEmpty())
        assertEquals(1, parsed.skippedEntries)
    }
}
