package com.wireturn.app.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class SubscriptionUpdateScheduleTest {
    @Test fun disabledScheduleHasNoTimerAndReenableWaitsForFreshMinute() = runBlocking {
        val enabled = MutableStateFlow(false)
        val minuteElapsed = Channel<Unit>(Channel.RENDEZVOUS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val delays = mutableListOf<Long>()
        var pending = 0
        var ticks = 0
        val job = scope.launch {
            runSubscriptionUpdateSchedule(enabled, wait = { duration ->
                delays += duration
                pending++
                try { minuteElapsed.receive() } finally { pending-- }
            }) { ticks++ }
        }
        try {
            yield()
            assertTrue(delays.isEmpty())
            assertEquals(0, ticks)
            enabled.value = true
            yield()
            assertEquals(listOf(60_000L), delays)
            assertEquals("Enabling never fetches immediately", 0, ticks)
            minuteElapsed.send(Unit)
            yield()
            assertEquals(1, ticks)
            assertEquals(listOf(60_000L, 60_000L), delays)
            assertEquals(1, pending)
            enabled.value = false
            yield()
            assertEquals("Disabling cancels the pending minute", 0, pending)
            assertTrue("Disabled time has no timer receiver", minuteElapsed.trySend(Unit).isFailure)
            assertEquals(1, ticks)
            enabled.value = true
            yield()
            assertEquals(listOf(60_000L, 60_000L, 60_000L), delays)
            assertEquals("Re-enabling never replays missed ticks", 1, ticks)
            minuteElapsed.send(Unit)
            yield()
            assertEquals(2, ticks)
        } finally {
            job.cancelAndJoin()
            scope.cancel()
            minuteElapsed.close()
        }
        assertEquals(0, pending)
    }

    @Test fun disablingTimerLeavesManagerOwnedFetchRunning() = runBlocking {
        val enabled = MutableStateFlow(true)
        val minuteElapsed = Channel<Unit>(Channel.RENDEZVOUS)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fetchFinished = CompletableDeferred<Unit>()
        var fetch: Job? = null
        var completed = false
        val scheduler = managerScope.launch {
            runSubscriptionUpdateSchedule(enabled, wait = { minuteElapsed.receive() }) {
                fetch = managerScope.launch {
                    fetchFinished.await()
                    completed = true
                }
            }
        }
        try {
            yield()
            minuteElapsed.send(Unit)
            yield()
            assertTrue(fetch?.isActive == true)
            enabled.value = false
            yield()
            assertTrue("The timer does not own an in-flight fetch", fetch?.isActive == true)
            fetchFinished.complete(Unit)
            fetch?.join()
            assertTrue(completed)
        } finally {
            scheduler.cancelAndJoin()
            managerScope.cancel()
            minuteElapsed.close()
        }
    }
}
