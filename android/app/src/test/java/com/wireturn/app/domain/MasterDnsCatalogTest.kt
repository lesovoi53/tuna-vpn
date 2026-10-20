package com.wireturn.app.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MasterDnsCatalogTest {
    @Test fun numericEndpointsNormalizeWithoutDnsResolution() {
        assertEquals("192.0.2.1:53", endpoint("192.0.2.1").canonical)
        assertEquals("192.0.2.1:5353", endpoint("192.0.2.1:5353").canonical)
        assertEquals("[2001:db8:0:0:0:0:0:1]:53", endpoint("2001:DB8::1").canonical)
        assertEquals(endpoint("2001:db8::1"), endpoint("[2001:0db8:0000:0000:0000:0000:0000:1]:53"))
        assertEquals("[0:0:0:0:0:0:0:1]:65535", endpoint("[::1]:65535").canonical)
        assertEquals(endpoint("192.0.2.1"), endpoint("::ffff:192.0.2.1"))
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 1), endpoint("192.0.2.1").address.address)
        assertEquals(16, endpoint("2001:db8::1").address.address.size)
    }

    @Test fun malformedAndNonliteralEndpointsAreRejected() {
        for (value in listOf(
            "dns.example.invalid", "https://192.0.2.1", "192.0.2", "192.0.2.256",
            "192.000.2.1", "3221225985", "0xc0000201", "192.0.2.1:0", "192.0.2.1:65536",
            "192.0.2.1:-1", "192.0.2.1:+53", "192.0.2.1:abc", "[192.0.2.1]:53",
            "2001:db8:::1", "2001::db8::1", "[::1", "[::1]:", "[::1]trailing",
            "fe80::1%wlan0", "2001:db8:0:0:0:0:0:0:1", "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8::", "2001:db8:gggg::1", "192.0.2.1 53", ""
        )) assertNull(value, MasterDnsEndpoint.parse(value))
    }

    @Test fun listHandlesBomCommentsDuplicatesAndRejectedRows() {
        val result = MasterDnsCatalog.parse(
            "\uFEFF# candidates\n192.0.2.1\n192.0.2.1:53 # duplicate\n[2001:db8::1]:5353\nbad.invalid\n<html>\n".toByteArray()
        )
        assertEquals(2, result.endpoints.size)
        assertEquals(2, result.rejected)
    }

    @Test fun byteAndEndpointLimitsFailClosed() {
        assertFails { MasterDnsCatalog.parse(ByteArray(MasterDnsCatalog.MAX_BYTES + 1)) }
        assertFails { MasterDnsCatalog.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        val many = buildString {
            repeat(MasterDnsCatalog.MAX_ENDPOINTS + 1) { append("2001:db8:${(it ushr 16).toString(16)}:${(it and 65535).toString(16)}::1\n") }
        }
        assertFails { MasterDnsCatalog.parse(many.toByteArray()) }
    }

    @Test fun localImportPersistsAndOfflineBackupRoundTrips() = runBlocking {
        withDirectory { directory ->
            val catalog = MasterDnsCatalog(directory, download = { error("No network allowed") })
            assertNull(catalog.load())
            val imported = catalog.importList(ByteArrayInputStream("192.0.2.1\n[2001:db8::1]:5353\n".toByteArray()))
            assertEquals(2, imported.count)
            assertFalse(imported.partial)
            val loaded = requireNotNull(MasterDnsCatalog(directory).load())
            assertEquals(imported, loaded)
            val backup = ByteArrayOutputStream()
            catalog.exportList(loaded, backup)
            assertEquals(imported.endpoints, catalog.importList(ByteArrayInputStream(backup.toByteArray())).endpoints)
        }
    }

    @Test fun partialSourceFailureIsVisibleAndDuplicatesAreMerged() = runBlocking {
        withDirectory { directory ->
            val sources = listOf(
                MasterDnsCatalogSource("first", "https://first.example.invalid/list"),
                MasterDnsCatalogSource("failed", "https://failed.example.invalid/list"),
                MasterDnsCatalogSource("last", "https://last.example.invalid/list"),
            )
            val catalog = MasterDnsCatalog(directory, sources) { url ->
                when {
                    "first." in url -> "192.0.2.1\n".toByteArray()
                    "last." in url -> "192.0.2.1:53\n192.0.2.2\nbad.invalid\n".toByteArray()
                    else -> error("Sensitive transport detail must not be displayed")
                }
            }
            val progress = mutableListOf<MasterDnsCatalogProgress>()
            val result = catalog.update { progress += it }
            assertEquals(2, result.count)
            assertTrue(result.partial)
            assertEquals(3, result.sources.size)
            assertNotNull(result.sources[1].error)
            assertFalse(result.sources[1].error!!.contains("Sensitive"))
            assertEquals(1, result.sources[2].rejected)
            assertEquals(3, progress.last().completed)
            assertEquals(result, catalog.load())
        }
    }

    @Test fun failedRefreshInvalidImportAndCancellationPreserveCache() = runBlocking {
        withDirectory { directory ->
            val initial = MasterDnsCatalog(directory).importList(ByteArrayInputStream("192.0.2.1".toByteArray()))
            val originalBytes = File(directory, "catalog-v1.bin").readBytes()
            val failed = MasterDnsCatalog(directory, download = { error("offline") })
            try { failed.update(); fail("Expected failure") } catch (_: IllegalArgumentException) { }
            try { failed.importList(ByteArrayInputStream("<html>invalid</html>".toByteArray())); fail("Expected failure") }
            catch (_: IllegalArgumentException) { }
            val cancelled = MasterDnsCatalog(directory, download = { throw CancellationException("cancelled") })
            try { cancelled.update(); fail("Expected cancellation") } catch (_: CancellationException) { }
            assertArrayEquals(originalBytes, File(directory, "catalog-v1.bin").readBytes())
            assertEquals(initial, failed.load())
            assertEquals(listOf("catalog-v1.bin"), directory.listFiles()!!.map { it.name })
        }
    }

    @Test fun insecureSourceIsNeverDownloaded() = runBlocking {
        withDirectory { directory ->
            var calls = 0
            val catalog = MasterDnsCatalog(directory, listOf(MasterDnsCatalogSource("unsafe", "http://example.invalid/list"))) {
                calls++
                "192.0.2.1".toByteArray()
            }
            try { catalog.update(); fail("Expected failure") } catch (_: IllegalArgumentException) { }
            assertEquals(0, calls)
            assertNull(catalog.load())
        }
    }

    @Test fun httpsRedirectMayContinueWithoutRealNetwork() = runBlocking {
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requested += request.url.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (requested.size == 1) 302 else 200).message("synthetic")
                .apply { if (requested.size == 1) header("Location", "https://mirror.example.invalid/list") }
                .body("192.0.2.1\n".toResponseBody()).build()
        }.build()
        assertEquals("192.0.2.1\n", MasterDnsCatalog.downloadHttps("https://source.example.invalid/list", client).toString(Charsets.UTF_8))
        assertEquals(2, requested.size)
    }

    @Test fun httpsRedirectCannotDowngradeOrAddCredentials() = runBlocking {
        for (location in listOf("http://mirror.example.invalid/list", "https://user:password@mirror.example.invalid/list")) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(302).message("synthetic").header("Location", location)
                    .body("".toResponseBody()).build()
            }.build()
            try {
                MasterDnsCatalog.downloadHttps("https://source.example.invalid/list", client)
                fail("Expected unsafe redirect rejection")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, calls)
        }
    }

    private fun endpoint(value: String) = requireNotNull(MasterDnsEndpoint.parse(value))

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }

    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("masterdns-catalog-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
