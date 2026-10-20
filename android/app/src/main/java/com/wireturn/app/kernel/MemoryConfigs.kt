package com.wireturn.app.kernel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Virtual names scoped to a runtime directory. Configuration bytes never enter the filesystem. */
object MemoryConfigs {
    private const val MAX_FRAME = 16 * 1024 * 1024
    private val contents = linkedMapOf<String, String>()

    @Synchronized fun write(file: File, text: String): File {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME) { "Runtime configuration too large" }
        contents[file.absolutePath] = text
        return file
    }

    @Synchronized fun remove(file: File) { contents.remove(file.absolutePath) }
    @Synchronized fun removeDirectory(directory: File) {
        val prefix = directory.absolutePath + File.separator
        contents.keys.removeAll { it.startsWith(prefix) }
    }

    internal data class Frame(val bytes: ByteArray, val environment: Map<String, String>)

    /** Snapshot dependencies atomically so a concurrent replacement cannot mix generations. */
    @Synchronized internal fun frame(args: List<String>, environment: Map<String, String>): Frame {
        require(args.size in 1..64 && args.all { '\u0000' !in it }) { "Invalid runtime arguments" }
        val selected = linkedMapOf<String, String>()
        // JSON manifests escape Windows separators (host tests) and quotes in paths.
        fun jsonPath(path: String) = path.replace("\\", "\\\\").replace("\"", "\\\"")
        var references = args + environment.values
        while (true) {
            val added = contents.filter { (path, _) -> path !in selected &&
                references.any { path in it || jsonPath(path) in it } }
            if (added.isEmpty()) break
            selected.putAll(added)
            require(selected.size <= 32) { "Too many runtime configurations" }
            references = added.values.toList()
        }
        val tokens = selected.keys.associateWith { "__TUNA_MEM_${UUID.randomUUID().toString().replace("-", "")}__" }
        fun substitute(text: String): String {
            var result = text
            for (path in tokens.keys.sortedByDescending { it.length }) {
                result = result.replace(jsonPath(path), tokens.getValue(path)).replace(path, tokens.getValue(path))
            }
            return result
        }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(buffer.size().toLong() + 4 + bytes.size <= MAX_FRAME) { "Runtime configuration too large" }
                out.writeInt(bytes.size); out.write(bytes)
            }
            out.writeBytes("TUNAMEM1")
            out.writeInt(args.size)
            args.forEach { field(substitute(it)) }
            out.writeInt(selected.size)
            selected.forEach { (path, text) -> field(tokens.getValue(path)); field(substitute(text)) }
        }
        require(buffer.size() <= MAX_FRAME) { "Runtime configuration too large" }
        return Frame(buffer.toByteArray(), environment.mapValues { substitute(it.value) })
    }

    fun start(builder: ProcessBuilder): Process {
        val args = builder.command().toList()
        val frame = frame(args, builder.environment())
        val launcher = File(File(args.first()).parentFile, "libtuna-runtime.so")
        check(launcher.isFile && launcher.canExecute()) { "Memory configuration launcher unavailable" }
        builder.command(launcher.absolutePath)
        builder.environment().putAll(frame.environment)
        val process = builder.start()
        val writer = FutureTask {
            // qWDTT continues to receive control messages through this same stream after exec.
            process.outputStream.write(frame.bytes)
            process.outputStream.flush()
        }
        Thread(writer, "tuna-config-transfer").apply { isDaemon = true; start() }
        try {
            writer.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            process.destroyForcibly()
            writer.cancel(true)
            throw java.io.IOException("Memory configuration transfer failed")
        } finally {
            if (writer.isDone) frame.bytes.fill(0)
        }
        return process
    }
}
