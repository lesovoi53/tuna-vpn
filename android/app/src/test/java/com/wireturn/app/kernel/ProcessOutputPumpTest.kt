package com.wireturn.app.kernel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessOutputPumpTest {
    @Test(timeout = 5_000) fun severityParserDoesNotTerminateOnDestinationOrTlsErrorText() = runBlocking {
        val child = Child()
        val expected = listOf(
            "INFO[0001] outbound connection to fatal.example:443",
            "ERROR[0002] remote error: tls: fatal alert",
            "INFO[0003] next connection is ready"
        )
        expected.forEach { child.emit(it + "\n") }; child.finish()
        val lines = mutableListOf<String>()
        drainProcessOutput(child, { fail("Ordinary connection messages must not terminate the core") }) {
            lines += it; VpnCoreLog.isFatal(it)
        }
        assertEquals(expected, lines)
        assertEquals(0, child.forced.get())
    }

    @Test(timeout = 5_000) fun fatalOutputDrainsCleanupBeforeClosingPipe() = runBlocking {
        val child = Child()
        child.emit("fatal\n")
        val callbacks = AtomicInteger()
        var cleanup: Thread? = null
        try {
            drainProcessOutput(child, terminate = {
                child.signals.incrementAndGet()
                cleanup = Thread {
                    try {
                        // More than the pipe capacity: a reader that stops on fatal breaks cleanup.
                        repeat(128) { child.emit("release allocation " + "x".repeat(1024) + "\n") }
                        child.cleaned = true
                    } finally { child.finish() }
                }.apply { isDaemon = true; start() }
            }, graceMillis = 2_000) {
                callbacks.incrementAndGet()
                it == "fatal"
            }
            assertTrue("SIGTERM cleanup must finish with stdout still open", child.cleaned)
            assertEquals(1, child.signals.get())
            assertEquals(0, child.forced.get())
            assertEquals("No new state changes during cleanup", 1, callbacks.get())
            assertFalse(child.isAlive)
        } finally { child.finish(); cleanup?.join(1000) }
    }

    @Test(timeout = 5_000) fun unresponsiveChildIsKilledAtDeadline() = runBlocking {
        val child = Child(); child.emit("fatal\n")
        try {
            drainProcessOutput(child, { child.signals.incrementAndGet() }, graceMillis = 40) { true }
            assertEquals(1, child.signals.get())
            assertTrue(child.forced.get() >= 1)
            assertFalse(child.isAlive)
        } finally { child.finish() }
    }

    @Test(timeout = 5_000) fun closingStdoutDoesNotCancelTerminationDeadline() = runBlocking {
        val child = Child(); child.emit("fatal\n")
        try {
            drainProcessOutput(child, {
                child.signals.incrementAndGet()
                child.closeOutput() // Still alive after closing stdout.
            }, graceMillis = 40) { true }
            assertFalse(child.isAlive)
            assertTrue(child.forced.get() >= 1)
        } finally { child.finish() }
    }

    @Test(timeout = 5_000) fun ordinaryExitIsNotTreatedAsFailure() = runBlocking {
        val child = Child(); child.emit("ready\nconnected\n"); child.finish()
        val lines = mutableListOf<String>()
        drainProcessOutput(child, { fail("Unexpected SIGTERM") }) { lines += it; false }
        assertEquals(listOf("ready", "connected"), lines)
        assertEquals(0, child.forced.get())
    }

    private class Child : Process() {
        private val output = PipedOutputStream()
        private val input = PipedInputStream(output, 4096)
        private val ended = CountDownLatch(1)
        val signals = AtomicInteger()
        val forced = AtomicInteger()
        @Volatile var cleaned = false
        fun emit(text: String) { output.write(text.toByteArray()); output.flush() }
        fun closeOutput() { output.close() }
        fun finish() { ended.countDown(); runCatching { output.close() } }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = ended.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun isAlive() = ended.count != 0L
        override fun destroy() { finish() }
        override fun destroyForcibly(): Process { forced.incrementAndGet(); finish(); return this }
    }
}
