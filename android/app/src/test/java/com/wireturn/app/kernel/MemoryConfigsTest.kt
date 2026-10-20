package com.wireturn.app.kernel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MemoryConfigsTest {
    @Test fun filesRemainReadableAcrossRestartsAndAreRemovedByOwner() {
        val root = Files.createTempDirectory("tuna-config-test").toFile()
        try {
            val first = File(root, "first").apply { mkdir() }
            val second = File(root, "second").apply { mkdir() }
            val child = MemoryConfigs.write(File(first, "resolvers"), "synthetic-resolver")
            val parent = MemoryConfigs.write(File(first, "config"), child.absolutePath)
            val unrelated = MemoryConfigs.write(File(second, "config"), "other-session")
            repeat(2) { assertEquals("synthetic-resolver", File(parent.readText()).readText()) }
            MemoryConfigs.removeDirectory(first)
            assertFalse(child.exists()); assertFalse(parent.exists()); assertTrue(unrelated.exists())
            MemoryConfigs.remove(unrelated); MemoryConfigs.remove(unrelated)
            assertFalse(unrelated.exists())
        } finally { MemoryConfigs.removeDirectory(root); root.deleteRecursively() }
    }
    @Test fun replacementIsCompleteAndLeavesNoStagingFile() {
        val root = Files.createTempDirectory("tuna-config-test").toFile()
        try {
            val file = File(root, "config")
            MemoryConfigs.write(file, "old")
            MemoryConfigs.write(file, "new-content")
            assertEquals("new-content", file.readText())
            assertEquals(listOf("config"), root.listFiles()!!.map { it.name })
            if (file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
            }
        } finally { MemoryConfigs.removeDirectory(root); root.deleteRecursively() }
    }
}
