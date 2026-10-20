package com.wireturn.app.kernel

import android.content.Context
import java.io.Closeable
import java.io.File

/** Owned by the HEV session and removed only after its native reader has joined. */
class PrivateRuntimeConfigFile private constructor(private val runtime: PrivateRuntimeDirectory) : Closeable {
    val path: String = File(runtime.directory, "hev.yaml").absolutePath
    override fun close() = runtime.close()
    companion object {
        fun create(context: Context, text: String): PrivateRuntimeConfigFile {
            val runtime = PrivateRuntimeDirectory(context, "hev")
            return try {
                PrivateRuntimeConfigFile(runtime).also { MemoryConfigs.write(File(it.path), text) }
            } catch (failure: Throwable) {
                runtime.close()
                throw failure
            }
        }
    }
}
