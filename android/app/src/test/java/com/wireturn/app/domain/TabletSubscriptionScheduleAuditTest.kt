package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Regressions captured during the tablet audit. */
class TabletSubscriptionScheduleAuditTest {
    @Test fun firstImportMustUseTheIntervalReceivedFromTheSubscription() = runBlocking {
        var time = 0L
        var persistedIntervalMinutes: Int? = null
        var requests = 0
        val coordinator = SubscriptionRefreshCoordinator<Boolean> { time }
        // Same call ordering as ProfileManager.coordinatedFetch: read settings,
        // pass interval, fetch and persist the newly received interval in action.
        suspend fun fetch(automatic: Boolean): Boolean? = coordinator.run(
            "https://example.invalid/sub", automatic,
            (persistedIntervalMinutes ?: 1440).toLong() * 60_000, { it }
        ) {
            requests++
            persistedIntervalMinutes = 20
            true
        }
        assertEquals(true, fetch(false))
        time = 20L * 60_000
        assertEquals("The first imported 20-minute interval must not wait 24 hours", true, fetch(true))
        assertEquals(2, requests)
    }

    @Test fun shorteningAnIntervalMustNotKeepTheOldLongDeadline() = runBlocking {
        var time = 0L
        val coordinator = SubscriptionRefreshCoordinator<Boolean> { time }
        assertEquals(true, coordinator.run("sub", false, 86_400_000, { it }) { true })
        // User changes an existing subscription from daily to every 20 minutes.
        time = 20L * 60_000
        assertEquals(true, coordinator.run("sub", true, 1_200_000, { it }) { true })
    }

    @Test fun manySimultaneousScreensStillFetchOnlyOnce() = runBlocking {
        val coordinator = SubscriptionRefreshCoordinator<Boolean>()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        val callers = List(32) {
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.run("sub", it % 2 == 0, 60_000, { it }) {
                    requests++
                    finish.await()
                    true
                }
            }
        }
        finish.complete(Unit)
        assertTrue(callers.awaitAll().all { it == true })
        assertEquals(1, requests)
    }

    @Test fun increasingIntervalDelaysSuccessButChangingIntervalDoesNotEraseFailureBackoff() = runBlocking {
        var time = 0L
        val coordinator = SubscriptionRefreshCoordinator<Boolean> { time }
        assertEquals(true, coordinator.run("sub", false, 1_200_000, { it }) { true })
        time = 1_200_000
        assertNull(coordinator.run("sub", true, 86_400_000, { it }) { true })
        assertEquals(false, coordinator.run("sub", false, 86_400_000, { it }) { false })
        time += 59_999
        assertNull(coordinator.run("sub", true, 60_000, { it }) { true })
        time++
        assertEquals(true, coordinator.run("sub", true, 60_000, { it }) { true })
    }
}
