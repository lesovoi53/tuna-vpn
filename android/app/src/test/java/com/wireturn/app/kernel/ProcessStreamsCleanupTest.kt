package com.wireturn.app.kernel

import java.io.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProcessStreamsCleanupTest {
    @Test fun reapedChildReleasesUnusedPipesButLetsOwnerDrainStdout() = runBlocking {
        for (initiallyAlive in listOf(true, false)) {
            val child = Child(initiallyAlive)
            terminateCoreProcess(child, { child.alive = false })
            assertTrue("Child stdin must not wait for GC", child.stdin.closed)
            assertTrue("Unused stderr must not wait for GC", child.stderr.closed)
            assertFalse("Output owner still needs to drain the final diagnostic", child.stdout.closed)
            assertEquals("final diagnostic", child.inputStream.bufferedReader().use { it.readText() })
        }
    }
    private class TrackedInput(text: String = "") : ByteArrayInputStream(text.toByteArray()) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private class TrackedOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private class Child(var alive: Boolean) : Process() {
        val stdin = TrackedOutput()
        val stdout = TrackedInput("final diagnostic")
        val stderr = TrackedInput()
        override fun getOutputStream() = stdin
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun isAlive() = alive
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue() = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { alive = false }
    }
}
