package com.wireturn.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RotatingDiagnosticStoreTest {
    private val opened = mutableListOf<RotatingDiagnosticStore>()
    @org.junit.After fun closeStores() { opened.forEach { it.close() } }

    private fun store(directory: File, maxFiles: Int = RotatingDiagnosticStore.MAX_FILES,
        maxFileBytes: Int = RotatingDiagnosticStore.MAX_FILE_BYTES, minimumUsableBytes: Long = 0,
        usableBytes: () -> Long = { directory.usableSpace }): RotatingDiagnosticStore =
        RotatingDiagnosticStore(directory, maxFiles, maxFileBytes, minimumUsableBytes, usableBytes).also { opened += it }
    @get:Rule val temporary = TemporaryFolder()

    @Test fun configuredLimitIsExactlyTenDecimalGigabytesWithoutPreallocation() {
        val store = store(temporary.newFolder())
        assertEquals(10_000_000_000L, RotatingDiagnosticStore.MAX_FILES.toLong() * RotatingDiagnosticStore.MAX_FILE_BYTES)
        assertEquals(10_000_000_000L, RotatingDiagnosticStore.MAX_STORAGE_BYTES)
        assertTrue(store.files().isEmpty())
    }

    @Test fun lowSpaceRejectsNewWritesAndKeepsExistingEvidence() {
        val directory = temporary.newFolder()
        var available = 200L
        val store = store(directory, 2, 20, minimumUsableBytes = 100, usableBytes = { available })
        store.append("retained")
        available = 100
        try {
            store.append("cannot fit")
            fail("Expected low-space protection")
        } catch (_: DiagnosticStorageLowSpaceException) {
            assertEquals("retained\n", store.files().single().readText())
        }
        available = 200
        store.append("resumed")
        assertEquals("retained\nresumed\n", store.files().single().readText())
    }

    @Test fun rotationAlwaysBoundsDiskAndRetainsNewestRecords() {
        val store = store(temporary.newFolder(), maxFiles = 3, maxFileBytes = 12)
        repeat(20) { index ->
            store.append("record-$index")
            assertTrue(store.files().size <= 3)
            assertTrue(store.files().all { it.length() <= 12 })
            assertTrue(store.files().sumOf { it.length() } <= 36)
        }
        assertEquals(listOf("record-17\n", "record-18\n", "record-19\n"), store.files().map { it.readText() })
    }

    @Test fun reopenAppendsAndContinuesSequenceWithoutOverwritingNewestFile() {
        val directory = temporary.newFolder()
        store(directory, 3, 12).use { it.append("first"); it.append("second") }
        val reopened = store(directory, 3, 12)
        reopened.append("third")
        assertEquals("first\nsecond\nthird\n", reopened.files().joinToString("") { it.readText() })
        assertEquals(listOf("diagnostic-00000000000000000001.log", "diagnostic-00000000000000000002.log", "diagnostic-00000000000000000003.log"), reopened.files().map { it.name })
    }

    @Test fun zipContainsOnlyMetadataAndFilesInChronologicalOrderAndLeavesOutputOpen() {
        val directory = temporary.newFolder()
        File(directory, "config.json").writeText("must never export")
        val store = RotatingDiagnosticStore(directory, 3, 8)
        store.append("first")
        store.append("second")
        store.append("third")
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        store.exportTo(output, "test metadata")
        assertFalse(closed)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(listOf("metadata.txt") + store.files().map { it.name }, entries.keys.toList())
        assertEquals(listOf("test metadata", "first\n", "second\n", "third\n"), entries.values.toList())
    }

    @Test fun hugeUtf8RecordIsTruncatedAtCodePointAndStaysBounded() {
        val store = RotatingDiagnosticStore(temporary.newFolder(), 2, 10)
        store.append("😀".repeat(100))
        assertEquals("😀😀\n", store.files().single().readText())
        assertTrue(store.files().single().length() <= 10)
        store.append("next")
        assertTrue(store.files().sumOf { it.length() } <= 20)
    }

    @Test fun reopenPrunesOlderFilesButDoesNotTouchUnrelatedFiles() {
        val directory = temporary.newFolder()
        val unrelated = File(directory, "readme.txt").apply { writeText("keep") }
        val large = RotatingDiagnosticStore(directory, 8, 32)
        repeat(8) { large.append("entry-$it".padEnd(25, '.')) }
        val small = RotatingDiagnosticStore(directory, 2, 16)
        assertEquals(2, small.files().size)
        assertTrue(small.files().all { it.length() <= 16 })
        assertTrue(small.files().first().readText().startsWith("entry-6"))
        assertEquals("keep", unrelated.readText())
    }

    @Test fun redactedEventSurvivesStoreAndExportWithoutCredentials() {
        val store = RotatingDiagnosticStore(temporary.newFolder())
        store.append(DiagnosticRedactor.clean("password=hunter2 https://private.example/path?token=abc"))
        val text = store.files().single().readText()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("private.example"))
        assertTrue(text.contains("<redacted>"))
    }
}
