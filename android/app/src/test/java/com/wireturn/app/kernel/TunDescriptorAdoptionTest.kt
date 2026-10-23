package com.wireturn.app.kernel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TunDescriptorAdoptionTest {
    private class Descriptor : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test(timeout = 5000) fun lateEstablishAfterStopAndDestroyClosesWithoutPublishing() = runBlocking {
        val ownerLock = Any()
        val stopping = AtomicBoolean(false)
        val enteredEstablish = CountDownLatch(1)
        val finishEstablish = CountDownLatch(1)
        val descriptor = Descriptor()
        var owned: Descriptor? = null
        var established = false
        val start = launch(Dispatchers.Default) {
            enteredEstablish.countDown()
            check(finishEstablish.await(3, TimeUnit.SECONDS))
            val startupContext = currentCoroutineContext()
            adoptTunDescriptor(ownerLock, descriptor,
                { startupContext.isActive && !stopping.get() }) {
                owned = it
                established = true
            }
        }
        try {
            assertTrue(enteredEstablish.await(2, TimeUnit.SECONDS))
            synchronized(ownerLock) { stopping.set(true) }
            start.cancel()
            repeat(2) { // Both Stop and onDestroy finish before establish returns.
                synchronized(ownerLock) {
                    owned?.close()
                    owned = null
                    established = false
                }
            }
            finishEstablish.countDown()
            start.join()
            assertNull(owned)
            assertFalse(established)
            assertEquals(1, descriptor.closes.get())
        } finally { finishEstablish.countDown(); start.cancelAndJoin() }
    }

    @Test fun cancelledOldRequestCannotAdoptAfterNewStartClearsStopping() = runBlocking {
        val descriptor = Descriptor()
        val job = Job().apply { cancel() }
        assertFalse(adoptTunDescriptor(Any(), descriptor, { job.isActive }) { fail("Retired descriptor published") })
        assertEquals(1, descriptor.closes.get())
    }

    @Test fun liveRequestPublishesOwnershipForSubsequentStop() {
        val descriptor = Descriptor()
        val ownerLock = Any()
        var owned: Descriptor? = null
        assertTrue(adoptTunDescriptor(ownerLock, descriptor, { true }) { owned = it })
        assertEquals(0, descriptor.closes.get())
        synchronized(ownerLock) { owned!!.close(); owned = null }
        assertEquals(1, descriptor.closes.get())
    }
}
