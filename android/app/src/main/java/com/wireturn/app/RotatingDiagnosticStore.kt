package com.wireturn.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Single-writer store. Only numbered diagnostic files in this private directory are managed. */
internal class RotatingDiagnosticStore(
    private val directory: File,
    private val maxFiles: Int = MAX_FILES,
    private val maxFileBytes: Int = MAX_FILE_BYTES,
    private val minimumUsableBytes: Long = 0,
    private val usableBytes: () -> Long = { directory.usableSpace },
) : AutoCloseable {
    // One serialized writer owns the directory. Reuse its unbuffered stream: each
    // record is still written immediately, without a directory scan/open/close per line.
    private var writer: FileOutputStream? = null
    private var writtenBytes = 0L
    companion object {
        const val MAX_FILES = 40
        const val MAX_FILE_BYTES = 250_000_000
        const val MAX_STORAGE_BYTES = 10_000_000_000L
    }
    private val namePattern = Regex("diagnostic-([0-9]{20})\\.log")

    init {
        require(maxFiles > 0 && maxFileBytes > 1 && minimumUsableBytes >= 0)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic directory" }
        // Recover from a previous version or interrupted write while retaining the newest files.
        files().dropLast(maxFiles).forEach { check(it.delete()) { "Cannot prune diagnostic file" } }
        files().filter { it.length() > maxFileBytes }.forEach {
            java.io.RandomAccessFile(it, "rw").use { stream -> stream.setLength(maxFileBytes.toLong()) }
        }
    }

    fun files(): List<File> = (directory.listFiles() ?: error("Cannot list diagnostic directory"))
        .filter { it.isFile && namePattern.matches(it.name) }.sortedBy { it.name }

    fun append(record: String, durable: Boolean = false) {
        val source = (record.replace('\r', ' ').replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8)
        val bytes = if (source.size <= maxFileBytes) source else {
            // Keep the newline and avoid cutting a UTF-8 code point at the size boundary.
            var end = maxFileBytes - 1
            while (end > 0 && source[end].toInt() and 0xc0 == 0x80) end--
            source.copyOf(end + 1).also { it[end] = '\n'.code.toByte() }
        }
        if (minimumUsableBytes > 0 && usableBytes() - bytes.size < minimumUsableBytes) {
            throw DiagnosticStorageLowSpaceException()
        }
        if (writer == null || writtenBytes + bytes.size > maxFileBytes) {
            close()
            val existing = files()
            val last = existing.lastOrNull()
            val target = if (last != null && last.length() + bytes.size <= maxFileBytes) last else {
                val sequence = last?.name?.let { namePattern.matchEntire(it)!!.groupValues[1].toLong() } ?: 0L
                check(sequence < Long.MAX_VALUE) { "Diagnostic sequence exhausted" }
                existing.take((existing.size - maxFiles + 1).coerceAtLeast(0)).forEach {
                    check(it.delete()) { "Cannot rotate diagnostic file" }
                }
                File(directory, "diagnostic-${(sequence + 1).toString().padStart(20, '0')}.log")
            }
            writer = FileOutputStream(target, true)
            writtenBytes = target.length()
        }
        try {
            val stream = checkNotNull(writer)
            stream.write(bytes)
            writtenBytes += bytes.size
            if (durable) stream.fd.sync()
        } catch (failure: Exception) {
            try { close() } catch (closing: Exception) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    override fun close() {
        val previous = writer
        writer = null
        writtenBytes = 0
        previous?.close()
    }

    /** Caller owns output; exporting does not close it. Call on the same serialized writer. */
    fun exportTo(output: OutputStream, metadata: String) {
        close()
        val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) {
            override fun close() { flush() }
        })
        zip.use {
            it.putNextEntry(ZipEntry("metadata.txt"))
            it.write(metadata.toByteArray(Charsets.UTF_8))
            it.closeEntry()
            files().forEach { file ->
                it.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input -> input.copyTo(it) }
                it.closeEntry()
            }
        }
    }
}

internal class DiagnosticStorageLowSpaceException : IOException("Diagnostic storage reserve reached")
