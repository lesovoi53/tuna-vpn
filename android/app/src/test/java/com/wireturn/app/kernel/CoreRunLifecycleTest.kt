package com.wireturn.app.kernel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoreRunLifecycleTest {
    @Test(timeout = 5_000) fun cancellingSilentReaderStopsChildWithoutExternalLifecycleStop() = runBlocking {
        val child = Child()
        val reader = launch(Dispatchers.Default) {
            drainProcessOutput(child, Process::destroy, stopOnCancellation = {
                terminateCoreProcess(child, Process::destroy, graceMillis = 40)
            }) { false }
        }
        try {
            assertTrue(child.readEntered.await(1, TimeUnit.SECONDS))
            withTimeout(1000) { reader.cancelAndJoin() }
            assertFalse("Owner cancellation must stop the child without relying on reader finally", child.isAlive)
        } finally {
            child.finish()
            withContext(NonCancellable) { reader.cancelAndJoin() }
        }
    }

    @Test fun ordinaryExitDoesNotInvokeCancellationStop() = runBlocking {
        val child = Child()
        child.emit("ready\n")
        child.finish()
        val lines = mutableListOf<String>()
        drainProcessOutput(child, { fail("Unexpected SIGTERM") }, stopOnCancellation = {
            fail("Normal EOF must not trigger cancellation cleanup")
        }) { lines += it; false }
        assertEquals(listOf("ready"), lines)
    }

    @Test(timeout = 5_000) fun restartingSilentCoreStopsItBeforeJoiningItsReader() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val child = Child()
        val current = AtomicReference<Child?>(child)
        val stop: suspend () -> Unit = {
            current.getAndSet(null)?.let { terminateCoreProcess(it, Process::destroy, graceMillis = 40) }
        }
        val previous = scope.launch {
            try { drainProcessOutput(child, { it.destroy() }) { false } }
            finally { stop() }
        }
        var replacement: Job? = null
        try {
            assertTrue(child.readEntered.await(1, TimeUnit.SECONDS))
            val started = CompletableDeferred<Unit>()
            replacement = scope.replaceCoreRun(previous, stop) {
                assertTrue("Old supervisor must finish before replacement starts", previous.isCompleted)
                assertFalse("Old process must have exited", child.isAlive)
                started.complete(Unit)
            }
            assertNotNull("Restart remains stuck joining the silent stdout reader",
                withTimeoutOrNull(500) { started.await() })
        } finally {
            child.finish()
            withContext(NonCancellable) { previous.cancelAndJoin(); replacement?.cancelAndJoin() }
            scope.cancel()
        }
    }

    @Test(timeout = 5_000) fun cancelledReaderStillDrainsGracefulShutdownWithoutCallbacks() = runBlocking {
        val child = Child()
        val callbacks = AtomicInteger()
        val reader = launch(Dispatchers.Default) {
            drainProcessOutput(child, { it.destroy() }, stopOnCancellation = { child.writeCleanupAndFinish() }) {
                callbacks.incrementAndGet(); false
            }
        }
        try {
            assertTrue(child.readEntered.await(1, TimeUnit.SECONDS))
            reader.cancel()
            assertNotNull("Cancellation must complete after the child releases resources",
                withTimeoutOrNull(1_500) { reader.join() })
            assertNull("Closing stdout broke the child's cleanup", child.cleanupFailure.get())
            assertTrue("Child must finish writing all cleanup output", child.cleaned.get())
            assertEquals("Cancelled output must not update service state", 0, callbacks.get())
        } finally {
            child.finish()
            withContext(NonCancellable) { reader.cancelAndJoin() }
        }
    }

    @Test(timeout = 5_000) fun cancellationInterruptsSuspendedParserButKeepsDraining() = runBlocking {
        val child = Child()
        val parsing = CompletableDeferred<Unit>()
        val parserCancelled = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger()
        val reader = launch(Dispatchers.Default) {
            drainProcessOutput(child, { it.destroy() }, stopOnCancellation = { child.writeCleanupAndFinish() }) {
                callbacks.incrementAndGet()
                parsing.complete(Unit)
                try { awaitCancellation() } finally { parserCancelled.complete(Unit) }
            }
        }
        try {
            child.emit("ready\n")
            withTimeout(1000) { parsing.await() }
            reader.cancel()
            withTimeout(1000) { parserCancelled.await() }
            withTimeout(1500) { reader.join() }
            assertNull(child.cleanupFailure.get())
            assertTrue(child.cleaned.get())
            assertEquals(1, callbacks.get())
        } finally {
            child.finish()
            withContext(NonCancellable) { reader.cancelAndJoin() }
        }
    }

    @Test(timeout = 5_000) fun rapidReplacementWaitsForAllEarlierCleanupAndSkipsCancelledStartup() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val child = Child()
        val retirementEntered = CompletableDeferred<Unit>()
        val releaseRetirement = CompletableDeferred<Unit>()
        val firstFinished = AtomicBoolean()
        val intermediateStarted = AtomicBoolean()
        val previous = scope.launch {
            try { drainProcessOutput(child, { it.destroy() }) { false } }
            finally { firstFinished.set(true) }
        }
        val stops = AtomicInteger()
        val stop: suspend () -> Unit = {
            if (stops.incrementAndGet() == 1) {
                retirementEntered.complete(Unit)
                releaseRetirement.await()
                child.finish()
            }
        }
        var middle: Job? = null
        var latest: Job? = null
        try {
            assertTrue(child.readEntered.await(1, TimeUnit.SECONDS))
            middle = scope.replaceCoreRun(previous, stop) { intermediateStarted.set(true) }
            assertNotNull("Retirement must reach process stop before joining",
                withTimeoutOrNull(500) { retirementEntered.await() })
            val started = CompletableDeferred<Unit>()
            latest = scope.replaceCoreRun(middle, stop) {
                assertTrue("Latest run must wait for the first supervisor's finally", firstFinished.get())
                assertFalse(child.isAlive)
                started.complete(Unit)
            }
            releaseRetirement.complete(Unit)
            assertNotNull(withTimeoutOrNull(1000) { started.await() })
            assertFalse("Superseded request must never start a core", intermediateStarted.get())
        } finally {
            releaseRetirement.complete(Unit)
            child.finish()
            withContext(NonCancellable) {
                previous.cancelAndJoin(); middle?.cancelAndJoin(); latest?.cancelAndJoin()
            }
            scope.cancel()
        }
    }

    private class Child : Process() {
        private val output = PipedOutputStream()
        private val pipe = PipedInputStream(output, 4096)
        private val ended = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val cleaned = AtomicBoolean()
        val cleanupFailure = AtomicReference<Exception?>()
        private val input = object : InputStream() {
            override fun read(): Int { readEntered.countDown(); return pipe.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readEntered.countDown(); return pipe.read(b, off, len)
            }
            override fun close() = pipe.close()
        }
        fun emit(text: String) { output.write(text.toByteArray()); output.flush() }
        fun writeCleanupAndFinish() {
            try {
                repeat(128) { emit("release " + "x".repeat(1024) + "\n") }
                cleaned.set(true)
            } catch (failure: Exception) { cleanupFailure.set(failure) }
            finally { finish() }
        }
        fun finish() { ended.countDown(); runCatching { output.close() } }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun isAlive(): Boolean = ended.count != 0L
        override fun destroy() = finish()
        override fun destroyForcibly(): Process { finish(); return this }
    }
}
