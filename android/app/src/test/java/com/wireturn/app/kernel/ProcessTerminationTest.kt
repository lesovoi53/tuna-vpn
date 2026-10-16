package com.wireturn.app.kernel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProcessTerminationTest {
    @Test(timeout = 5_000) fun cancellationCannotSkipForceOrReturnBeforeForcedChildExits() = runBlocking {
        val child = Child()
        val stopping = launch(Dispatchers.Default) {
            terminateCoreProcess(child, Process::destroy, graceMillis = 30, forceMillis = 1_500)
        }
        try {
            assertTrue(child.signalled.await(1, TimeUnit.SECONDS))
            stopping.cancel()
            assertTrue("Ignoring SIGTERM must trigger forced termination", child.forced.await(1, TimeUnit.SECONDS))
            delay(50)
            assertFalse("A force signal alone is not proof the old process exited", stopping.isCompleted)
            child.finish()
            withTimeout(1_000) { stopping.join() }
            assertFalse(child.isAlive)
        } finally {
            child.finish()
            stopping.cancelAndJoin()
        }
    }

    @Test fun processThatSurvivesForcedTerminationFailsWithinBoundedDeadline() = runBlocking {
        val child = Child()
        try {
            val failure = runCatching {
                withTimeout(1000) {
                    terminateCoreProcess(child, Process::destroy, graceMillis = 20, forceMillis = 40)
                }
            }.exceptionOrNull()
            assertTrue("Unreaped process must block replacement with an explicit failure", failure is IllegalStateException)
            assertTrue(child.isAlive)
            assertEquals(0L, child.forced.count)
        } finally { child.finish() }
    }

    private class Child : Process() {
        private val ended = CountDownLatch(1)
        val signalled = CountDownLatch(1)
        val forced = CountDownLatch(1)
        fun finish() = ended.countDown()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun isAlive(): Boolean = ended.count != 0L
        override fun destroy() = signalled.countDown()
        override fun destroyForcibly(): Process { forced.countDown(); return this }
    }
}
