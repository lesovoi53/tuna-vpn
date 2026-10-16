package com.wireturn.app.kernel

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreRunQueuedReplacementTest {
    @Test(timeout = 5_000)
    fun replacementCancelledBeforeDispatchStillRetiresItsPredecessor() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(dispatcher + scopeJob)
        val releaseRetirement = CompletableDeferred<Unit>()
        val retired = AtomicBoolean()
        val intermediateStarted = AtomicBoolean()
        val latestStarted = CompletableDeferred<Boolean>()
        val previous = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    releaseRetirement.await()
                    retired.set(true)
                }
            }
        }
        val stop: suspend () -> Unit = { releaseRetirement.complete(Unit) }

        try {
            val middle = scope.replaceCoreRun(previous, stop) {
                intermediateStarted.set(true)
            }
            // No queued continuation has run. A default launch would be cancelled before
            // entering its cleanup, dropping the dependency on the original supervisor.
            middle.cancel()
            val latest = scope.replaceCoreRun(middle, stop) {
                latestStarted.complete(previous.isCompleted && retired.get())
            }

            assertTrue("Latest replacement must finish within the deadline",
                dispatcher.runUntil { latest.isCompleted })
            assertTrue("Latest replacement must actually start", latestStarted.isCompleted)
            assertTrue("Latest startup must follow the original supervisor's retirement",
                latestStarted.await())
            assertFalse("Cancelled intermediate replacement must never start", intermediateStarted.get())
        } finally {
            releaseRetirement.complete(Unit)
            scope.cancel()
            withContext(NonCancellable) {
                assertTrue("Test cleanup must finish all queued supervisors",
                    dispatcher.runUntil { scopeJob.isCompleted })
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.add(block)
        }

        suspend fun runUntil(done: () -> Boolean): Boolean = withTimeoutOrNull(1_500) {
            while (!done()) {
                val next = queued.poll()
                if (next == null) delay(1) else {
                    next.run()
                    yield()
                }
            }
            true
        } ?: false
    }
}
