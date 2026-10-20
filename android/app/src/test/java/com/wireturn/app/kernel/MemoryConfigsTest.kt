package com.wireturn.app.kernel

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MemoryConfigsTest {
    private data class Decoded(val args: List<String>, val files: Map<String, String>)
    private fun decode(frame: MemoryConfigs.Frame): Decoded = DataInputStream(ByteArrayInputStream(frame.bytes)).use { input ->
        fun field(): String = ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
        val magic = ByteArray(8).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        assertEquals("TUNAMEM1", magic)
        val args = List(input.readInt()) { field() }
        val files = linkedMapOf<String, String>()
        repeat(input.readInt()) { files[field()] = field() }
        assertEquals(-1, input.read())
        Decoded(args, files)
    }

    @Test fun canaryNeverWrittenAndRestartKeepsTransitiveConfig() {
        val directory = Files.createTempDirectory("tuna-memory-test").toFile()
        try {
            val child = MemoryConfigs.write(File(directory, "resolvers"), "CANARY-private-credentials")
            val parent = MemoryConfigs.write(File(directory, "config"), "resolver=${child.absolutePath}")
            repeat(2) {
                val frame = MemoryConfigs.frame(listOf("/lib/core.so", "-config", parent.absolutePath), emptyMap())
                val result = decode(frame)
                assertEquals(2, result.files.size)
                val parentContent = result.files.getValue(result.args[2])
                assertEquals("CANARY-private-credentials", result.files.getValue(parentContent.substringAfter('=')))
                assertTrue(directory.listFiles()!!.isEmpty())
                assertFalse(result.args.joinToString().contains("CANARY"))
            }
            MemoryConfigs.removeDirectory(directory)
            assertTrue(decode(MemoryConfigs.frame(listOf("/lib/core.so", parent.absolutePath), emptyMap())).files.isEmpty())
        } finally { MemoryConfigs.removeDirectory(directory); directory.deleteRecursively() }
    }

    @Test fun environmentReferencesIncludeOnlyTheirOwnedFiles() {
        val directory = Files.createTempDirectory("tuna-memory-test").toFile()
        try {
            val policy = MemoryConfigs.write(File(directory, "policy"), "private-policy")
            MemoryConfigs.write(File(directory, "unrelated"), "other-session-secret")
            val frame = MemoryConfigs.frame(listOf("/lib/core.so"), mapOf("TUNA_ROUTING_FILE" to policy.absolutePath, "PATH" to "/bin"))
            val result = decode(frame)
            assertEquals(mapOf(frame.environment.getValue("TUNA_ROUTING_FILE") to "private-policy"), result.files)
            assertEquals("/bin", frame.environment["PATH"])
        } finally { MemoryConfigs.removeDirectory(directory); directory.deleteRecursively() }
    }

    @Test fun nulArgumentsFailClosed() {
        try { MemoryConfigs.frame(listOf("/lib/core.so", "secret\u0000tail"), emptyMap()); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
