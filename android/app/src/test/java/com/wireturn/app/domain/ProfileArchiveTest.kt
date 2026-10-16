package com.wireturn.app.domain

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ProfileArchiveTest {
    @Test fun plainFileMustNotSilentlyChangeMalformedUtf8() {
        val data = "{\"password\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            readProfileText(data.inputStream())
        }
    }

    @Test fun plainFileLimitCountsBytesAndAllowsExactBoundary() {
        val data = "{\"name\":\"Москва 🐟\"}".toByteArray(Charsets.UTF_8)
        assertEquals(data.toString(Charsets.UTF_8), readProfileText(data.inputStream(), data.size))
        assertThrows(IllegalArgumentException::class.java) {
            readProfileText(data.inputStream(), data.size - 1)
        }
    }

    @Test fun malformedUtf8MustNotSilentlyChangeAnImportedCredential() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("good.json")); it.write("{}".toByteArray()); it.closeEntry()
                it.putNextEntry(ZipEntry("broken.json"))
                it.write("{\"password\":\"".toByteArray())
                it.write(byteArrayOf(0xc3.toByte(), 0x28))
                it.write("\"}".toByteArray()); it.closeEntry()
            }
        }.toByteArray()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            readProfileArchive(zip.inputStream())
        }
    }

    private fun archive(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray().inputStream()

    @Test fun oldAndNewNamesAndUnicodeRemainReadable() {
        val entries = readProfileArchive(archive("wt_old.json" to "{\"name\":\"Тест\"}", "tuna_new.json" to "{}", "note.txt" to "ignored"))
        assertEquals(listOf("wt_old.json", "tuna_new.json"), entries.map { it.first })
        assertTrue(entries.first().second.contains("Тест"))
    }
    @Test fun compressedOversizedIgnoredEntryIsAlsoRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            readProfileArchive(archive("ok.json" to "{}", "ignored.bin" to "x".repeat(10000)), maxEntryBytes = 100)
        }
    }
    @Test fun cumulativeSizeAndEntryCountAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            readProfileArchive(archive("a.json" to "12345", "b.json" to "12345"), maxTotalBytes = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            readProfileArchive(archive("a.json" to "{}", "b.json" to "{}"), maxEntries = 1)
        }
    }
}
