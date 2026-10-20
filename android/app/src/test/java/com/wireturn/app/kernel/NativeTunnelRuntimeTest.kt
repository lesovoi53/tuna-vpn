package com.wireturn.app.kernel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class NativeTunnelRuntimeTest {
    @Test fun alreadyCancelledRequestMustNotEnterJni() = runBlocking {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val runtime = NativeTunnelRuntime({ _, _ -> starts.incrementAndGet(); true }, {})
        launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel()
            runtime.start("test.yaml", 42, AutoCloseable { closes.incrementAndGet() })
        }.join()
        assertEquals("A retired request must not launch a replacement tunnel", 0, starts.get())
        assertEquals(1, closes.get())
    }

    @Test(timeout = 5000) fun cancelledStartStillReleasesNativeTunnelBeforeClosingDescriptor() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val alive = AtomicBoolean(false)
        val stops = AtomicInteger()
        val closes = AtomicInteger()
        val mutex = Mutex()
        val runtime = NativeTunnelRuntime({ _, _ ->
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            alive.set(true)
            true
        }, { alive.set(false); stops.incrementAndGet() })
        val start = launch(Dispatchers.Default) { mutex.withLock {
            runtime.start("test.yaml", 42, AutoCloseable {
                assertFalse("JNI must stop before its config FD closes", alive.get())
                closes.incrementAndGet()
            })
        } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            start.cancel()
            assertEquals(0, closes.get())
            // Mirrors service Stop: queue cleanup while the synchronous JNI start still runs.
            val stop = async(Dispatchers.Default) {
                mutex.withLock {
                    runtime.stop()
                    !alive.get() // Descriptor can only be closed after the native owner is stopped.
                }
            }
            release.countDown()
            assertTrue("Cancelled JNI startup left an untracked live tunnel", stop.await())
            start.join()
            assertEquals(1, stops.get())
            assertEquals(1, closes.get())
            assertFalse(runtime.isRunning)
            assertFalse(runtime.stop())
        } finally { release.countDown(); start.cancelAndJoin(); runtime.stop() }
    }

    @Test fun successfulStartAndRepeatedCleanupStopExactlyOnce() = runBlocking {
        val stops = AtomicInteger()
        val closes = AtomicInteger()
        val runtime = NativeTunnelRuntime({ _, _ -> true }, { stops.incrementAndGet() })
        assertTrue(runtime.start("test.yaml", 42, AutoCloseable { closes.incrementAndGet() }))
        assertTrue(runtime.isRunning)
        assertEquals(0, closes.get())
        assertTrue(runtime.stop())
        assertFalse(runtime.stop())
        assertEquals(1, stops.get())
        assertEquals(1, closes.get())
    }

    @Test fun unsuccessfulStartDoesNotClaimNativeOwnership() = runBlocking {
        var closes = 0
        val runtime = NativeTunnelRuntime({ _, _ -> false }, { fail("Nothing to stop") })
        assertFalse(runtime.start("test.yaml", 42, AutoCloseable { closes++ }))
        assertFalse(runtime.isRunning)
        assertFalse(runtime.stop())
        assertEquals(1, closes)
    }

    @Test fun failedStopKeepsOwnershipForCleanupRetry() = runBlocking {
        var attempts = 0
        var closes = 0
        val runtime = NativeTunnelRuntime({ _, _ -> true }, {
            if (++attempts == 1) throw IllegalStateException("Synthetic JNI failure")
        })
        runtime.start("test.yaml", 42, AutoCloseable { closes++ })
        assertTrue(runCatching { runtime.stop() }.isFailure)
        assertTrue(runtime.isRunning)
        assertEquals(0, closes)
        assertTrue(runtime.stop())
        assertFalse(runtime.isRunning)
        assertEquals(1, closes)
    }

    @Test fun jniStartExceptionRetainsConfigurationUntilCleanupCompletes() = runBlocking {
        var closes = 0
        var stops = 0
        val runtime = NativeTunnelRuntime({ _, _ -> error("Synthetic partial JNI start") }, { stops++ })
        assertTrue(runCatching { runtime.start("/proc/self/fd/42", 7, AutoCloseable { closes++ }) }.isFailure)
        assertTrue(runtime.isRunning)
        assertEquals(0, closes)
        assertTrue(runtime.stop())
        assertEquals(1, stops)
        assertEquals(1, closes)
    }

    @Test fun replacementCannotCloseConfigurationStillOwnedByTheLiveTunnel() = runBlocking {
        val closed = mutableListOf<String>()
        val runtime = NativeTunnelRuntime({ _, _ -> true }, {})
        runtime.start("old", 7, AutoCloseable { closed += "old" })
        assertTrue(runCatching { runtime.start("new", 7, AutoCloseable { closed += "new" }) }.isFailure)
        assertEquals(listOf("new"), closed)
        assertTrue(runtime.isRunning)
        runtime.stop()
        assertEquals(listOf("new", "old"), closed)
    }
}
