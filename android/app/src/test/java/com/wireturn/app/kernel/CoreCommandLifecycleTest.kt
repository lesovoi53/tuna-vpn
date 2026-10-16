package com.wireturn.app.kernel

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoreCommandLifecycleTest {
    @Test(timeout = 5000)
    fun ordinaryStopCompletesCleanupBeforePublishingIdle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lifecycle = CoreCommandLifecycle(scope, Dispatchers.Unconfined)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val running = CompletableDeferred<Unit>()
        try {
            lifecycle.start({}) {
                running.complete(Unit)
                try { awaitCancellation() } finally { events.add("run ended") }
            }
            running.await()
            lifecycle.stop({}, { events.add("resources stopped") }, { events.add("idle") })
            lifecycle.job?.join()
            assertEquals(listOf("run ended", "resources stopped", "idle"), events.toList())
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000)
    fun stopStartStopSkipsIntermediateRunButFinishesBothCleanups() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lifecycle = CoreCommandLifecycle(scope, Dispatchers.Unconfined)
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            lifecycle.start({}) { awaitCancellation() }
            lifecycle.stop({}, { cleaning.complete(Unit); release.await(); events.add("cleanup1") }, { events.add("stale idle") })
            cleaning.await()
            lifecycle.start({}) { events.add("stale start") }
            lifecycle.stop({}, { events.add("cleanup2") }, { events.add("idle") })
            release.complete(Unit)
            lifecycle.job?.join()
            assertEquals(listOf("cleanup1", "cleanup2", "idle"), events.toList())
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test(timeout = 5000)
    fun startWaitsForStopCleanupAndDoesNotReceiveItsIdleCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lifecycle = CoreCommandLifecycle(scope, Dispatchers.Unconfined)
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            lifecycle.start({}) { awaitCancellation() }
            lifecycle.stop({}, {
                cleaning.complete(Unit)
                release.await()
                events.add("stop resources")
            }, { events.add("idle") })
            cleaning.await()
            lifecycle.start({}) { events.add("start"); started.complete(Unit) }
            assertNull("New run overtook old cleanup", withTimeoutOrNull(100) { started.await() })
            release.complete(Unit)
            withTimeout(2000) { started.await(); lifecycle.job?.join() }
            assertEquals(listOf("stop resources", "start"), events.toList())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }
}
