package com.wireturn.app.kernel

import java.io.Closeable

/** Same-process memfd for HEV's asynchronous JNI reader; close only after native stop joins. */
class MemoryFileDescriptor private constructor(private var descriptor: Int) : Closeable {
    val path: String get() { check(descriptor >= 0); return "/proc/self/fd/$descriptor" }
    @Synchronized override fun close() {
        val fd = descriptor
        // close(2) can release the FD even when it reports an error. Never retry a reused number.
        descriptor = -1
        if (fd >= 0) nativeClose(fd)
    }
    private external fun nativeClose(fd: Int)
    companion object {
        init { System.loadLibrary("tuna-memory") }
        @JvmStatic private external fun nativeCreate(bytes: ByteArray): Int
        fun create(text: String): MemoryFileDescriptor {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return try { MemoryFileDescriptor(nativeCreate(bytes).also { check(it >= 0) }) }
            finally { bytes.fill(0) }
        }
    }
}
