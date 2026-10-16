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
        val runtime = NativeTunnelRuntime({ _, _ -> starts.incrementAndGet(); true }, {})
        launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel()
            runtime.start("test.yaml", 42)
        }.join()
        assertEquals("A retired request must not launch a replacement tunnel", 0, starts.get())
    }

    @Test(timeout = 5000) fun cancelledStartStillReleasesNativeTunnelBeforeClosingDescriptor() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val alive = AtomicBoolean(false)
        val stops = AtomicInteger()
        val mutex = Mutex()
        val runtime = NativeTunnelRuntime({ _, _ ->
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            alive.set(true)
            true
        }, { alive.set(false); stops.incrementAndGet() })
        val start = launch(Dispatchers.Default) { mutex.withLock { runtime.start("test.yaml", 42) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            start.cancel()
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
            assertFalse(runtime.isRunning)
            assertFalse(runtime.stop())
        } finally { release.countDown(); start.cancelAndJoin(); runtime.stop() }
    }

    @Test fun successfulStartAndRepeatedCleanupStopExactlyOnce() = runBlocking {
        val stops = AtomicInteger()
        val runtime = NativeTunnelRuntime({ _, _ -> true }, { stops.incrementAndGet() })
        assertTrue(runtime.start("test.yaml", 42))
        assertTrue(runtime.isRunning)
        assertTrue(runtime.stop())
        assertFalse(runtime.stop())
        assertEquals(1, stops.get())
    }

    @Test fun unsuccessfulStartDoesNotClaimNativeOwnership() = runBlocking {
        val runtime = NativeTunnelRuntime({ _, _ -> false }, { fail("Nothing to stop") })
        assertFalse(runtime.start("test.yaml", 42))
        assertFalse(runtime.isRunning)
        assertFalse(runtime.stop())
    }

    @Test fun failedStopKeepsOwnershipForCleanupRetry() = runBlocking {
        var attempts = 0
        val runtime = NativeTunnelRuntime({ _, _ -> true }, {
            if (++attempts == 1) throw IllegalStateException("Synthetic JNI failure")
        })
        runtime.start("test.yaml", 42)
        assertTrue(runCatching { runtime.stop() }.isFailure)
        assertTrue(runtime.isRunning)
        assertTrue(runtime.stop())
        assertFalse(runtime.isRunning)
    }
}
