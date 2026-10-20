package com.wireturn.app.kernel

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Private temporary configurations. Historical name retained for existing callers.
 * Some Android SELinux policies forbid reopening memfd; use owner-only files instead.
 * Callers retain each file until their process/HEV reader has stopped, then remove it.
 */
object MemoryConfigs {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val owned = linkedSetOf<File>()

    @Synchronized fun write(file: File, text: String): File {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Runtime configuration too large" }
        val target = file.absoluteFile
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create runtime directory" }
        val temporary = try {
            Files.createTempFile(parent.toPath(), ".tuna-config-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            // Host tests on Windows; Android supports POSIX file permissions.
            Files.createTempFile(parent.toPath(), ".tuna-config-", ".tmp")
        }
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            owned.add(target)
            return file
        } finally {
            bytes.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized fun remove(file: File) {
        val target = file.absoluteFile
        check(!target.exists() || target.delete()) { "Cannot remove runtime configuration" }
        owned.remove(target)
    }

    @Synchronized fun removeDirectory(directory: File) {
        val prefix = directory.absolutePath + File.separator
        owned.filter { it.absolutePath.startsWith(prefix) }.forEach { remove(it) }
    }

    fun start(builder: ProcessBuilder): Process = builder.start()
}
