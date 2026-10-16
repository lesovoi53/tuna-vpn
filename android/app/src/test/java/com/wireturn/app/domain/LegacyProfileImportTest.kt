package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.data.SafeEnumTypeAdapterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LegacyProfileImportTest {
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val valid = """{"id":"valid","name":"Working","kernelConfig":{"type":"vpn","config":{"uri":"vless://11111111-2222-4333-8444-555555555555@192.0.2.1:9443?security=tls"}}}"""
    private val removed = """{"id":"removed","name":"Removed","kernelConfig":{"type":"olcrtc","config":{}}}"""

    @Test fun removedKernelInArrayDoesNotDiscardWorkingNeighbor() {
        val result = LegacyProfileImport.parse("[$valid,$removed]", gson)
        assertEquals(listOf("valid"), result.profiles.map { it.id })
        assertEquals(1, result.skippedEntries)
        assertEquals("Working", result.profiles.single().name)
    }

    @Test fun sanitizeFailureAndMalformedEntryDoNotDiscardNeighbors() {
        val invalidBundle = """{"id":"broken","uri":"unsupported-bundle://invalid"}"""
        val badField = """{"id":"wrong-type","name":{}}"""
        val result = LegacyProfileImport.parse("[$invalidBundle,$valid,$badField,null,5]", gson)
        assertEquals(listOf("valid"), result.profiles.map { it.id })
        assertEquals(4, result.skippedEntries)
    }

    @Test fun removedProfileObjectDoesNotAbortSanitizationOfWorkingObjects() {
        val working = LegacyProfileImport.parse(valid, gson).profiles.single()
        val (profiles, skipped) = LegacyProfileImport.prepare(listOf(
            "removed.json" to Profile("removed", "Removed", KernelConfig.Olcrtc()),
            "working.json" to working
        ), "Profile")
        assertEquals(listOf("working.json" to working), profiles)
        assertEquals(1, skipped)
    }

    @Test fun bundleRetainsMetadataAndRecommendationWhileSkippingBrokenEntry() {
        val result = LegacyProfileImport.parse("""{"version":1,"name":"Subscription","description":"Details",
            "profiles":[$removed,$valid],"activeProfileId":"valid","bytesUsed":123,"bytesTotal":456,
            "updateIntervalMinutes":60} """, gson)
        assertEquals(listOf("valid"), result.profiles.map { it.id })
        assertEquals(1, result.skippedEntries)
        assertEquals("Subscription", result.name)
        assertEquals("Details", result.description)
        assertEquals("valid", result.recommendedProfileId)
        assertEquals(123L, result.bytesUsed)
        assertEquals(456L, result.bytesTotal)
        assertEquals(60, result.updateIntervalMinutes)
    }

    @Test fun mixedZipFilesAndArraysKeepWorkingEntries() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                listOf("mixed.json" to "[$valid,$removed]", "second.json" to valid.replace("valid", "second"))
                    .forEach { (name, json) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(json.toByteArray())
                        zip.closeEntry()
                    }
            }
        }.toByteArray()
        val entries = readProfileArchive(bytes.inputStream()).map { LegacyProfileImport.parse(it.second, gson) }
        assertEquals(listOf("valid", "second"), entries.flatMap { it.profiles }.map { it.id })
        assertEquals(1, entries.sumOf { it.skippedEntries })
    }

    @Test fun allInvalidEntriesProduceNoImportCandidates() {
        val result = LegacyProfileImport.parse("[$removed,{},null]", gson)
        assertTrue(result.profiles.isEmpty())
        assertEquals(3, result.skippedEntries)
    }

    @Test fun corruptedContainersAndMetadataAreRejectedAsWholeDocuments() {
        for (json in listOf("[$valid", "null", "17", """{"profiles":{}}""",
            """{"profiles":[$valid],"bytesUsed":{}}""")) {
            assertThrows(RuntimeException::class.java) { LegacyProfileImport.parse(json, gson) }
        }
    }

    @Test fun versionedSnapshotsNeverFallBackToLegacyEntryTolerance() {
        for (schema in listOf("tuna.subscription", "tuna.connection-groups", "future.schema")) {
            assertThrows(IllegalArgumentException::class.java) {
                LegacyProfileImport.parse("""{"schema":"$schema","revision":-1,"profiles":[$valid,$removed]}""", gson)
            }
        }
    }

    @Test fun missingIdsUseOriginalContainerPositionAndExplicitIdsStayUnchanged() {
        val withoutId = valid.replace("\"id\":\"valid\",", "")
        repeat(2) {
            val result = LegacyProfileImport.parse("[$removed,$withoutId,$valid]", gson,
                missingId = { index, total -> "stable-$index-of-$total" })
            assertEquals(listOf("stable-1-of-3", "valid"), result.profiles.map { it.id })
        }
    }

    @Test fun cancellationAndVmFailuresAreNeverConvertedToSkippedItems() {
        val cancellation = CancellationException("cancel")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            LegacyProfileImport.itemOrNull<Profile> { throw cancellation }
        })
        val error = AssertionError("failure")
        assertSame(error, assertThrows(AssertionError::class.java) {
            LegacyProfileImport.itemOrNull<Profile> { throw error }
        })
    }

    @Test fun serverOnlyVkEntriesRemainImportableBeforeAuthentication() {
        val qwdtt = Profile("qwdtt", "Q", KernelConfig.Qwdtt(QwdttConfig(peer = "192.0.2.1:9443", password = "test-password")))
        val result = LegacyProfileImport.parse(gson.toJson(listOf(qwdtt)), gson)
        assertEquals(listOf("qwdtt"), result.profiles.map { it.id })
        assertEquals(0, result.skippedEntries)
        assertTrue(result.profiles[0].qwdttConfig.vkHashes.isEmpty())
    }

    @Test fun validChainCannotResurrectRemovedOrUnknownKernel() {
        val chain = """{"privateKey":"private-fixture","publicKey":"public-fixture","address":"10.0.0.2/32"}"""
        val standalone = Profile("chain", "Chain", wgConfig = WgConfig(privateKey = "private-fixture",
            publicKey = "public-fixture", address = "10.0.0.2/32"))
        assertEquals(1, LegacyProfileImport.parse(gson.toJson(standalone), gson).profiles.size)
        for (type in listOf("olcrtc", "turnable", "freeturn", "future-kernel")) {
            val json = """{"id":"removed","kernelConfig":{"type":"$type"},"wgConfig":$chain}"""
            val result = LegacyProfileImport.parse("[$valid,$json]", gson)
            assertEquals(listOf("valid"), result.profiles.map { it.id })
            assertEquals(1, result.skippedEntries)
        }
        val removedUri = """{"uri":"olcrtc://removed","wgConfig":$chain}"""
        assertTrue(LegacyProfileImport.parse(removedUri, gson).profiles.isEmpty())
        val invalidKernel = """{"kernelConfig":{"type":"unsupported","config":{"transport":"unknown"}},"wgConfig":$chain}"""
        val mixed = LegacyProfileImport.parse("[$valid,$invalidKernel]", gson)
        assertEquals(listOf("valid"), mixed.profiles.map { it.id })
        assertEquals(1, mixed.skippedEntries)
    }
}
