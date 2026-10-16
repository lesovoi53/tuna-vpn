package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRefreshCoordinatorTest {
    @Test fun screensAndManualRefreshShareOneRequest() = runBlocking {
        val c = SubscriptionRefreshCoordinator<Boolean>()
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { c.run("a", true, 60_000, { it }) { calls++; entered.complete(Unit); finish.await(); true } }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { c.run("a", false, 60_000, { it }) { calls++; false } }
        finish.complete(Unit)
        assertEquals(true, first.await()); assertEquals(true, second.await()); assertEquals(1, calls)
    }
    @Test fun backoffGrowsAndManualSuccessResetsIt() = runBlocking {
        var time = 0L; var calls = 0
        val c = SubscriptionRefreshCoordinator<Boolean> { time }
        suspend fun attempt(manual: Boolean = false, ok: Boolean = false) =
            c.run("a", !manual, 120_000, { it }) { calls++; ok }
        assertEquals(false, attempt())
        time = 59_999; assertNull(attempt())
        time = 60_000; assertEquals(false, attempt())
        time = 179_999; assertNull(attempt())
        assertEquals(true, attempt(manual = true, ok = true))
        time = 299_998; assertNull(attempt())
        time = 299_999; assertEquals(false, attempt())
        time = 359_999; assertEquals(false, attempt())
        assertEquals(5, calls)
    }
    @Test fun cancellationReleasesSlotAndDoesNotPenalizeRetry() = runBlocking {
        val c = SubscriptionRefreshCoordinator<Boolean>()
        val entered = CompletableDeferred<Unit>()
        val owner = launch { c.run("a", true, 60_000, { it }) { entered.complete(Unit); awaitCancellation() } }
        entered.await(); owner.cancelAndJoin()
        assertEquals(true, c.run("a", true, 60_000, { it }) { true })
    }
    @Test fun cancellingWaiterDoesNotCancelOwnerAndOtherSubscriptionsProceed() = runBlocking {
        val c = SubscriptionRefreshCoordinator<Boolean>()
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val owner = async { c.run("a", true, 60_000, { it }) { entered.complete(Unit); finish.await(); true } }
        entered.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { c.run("a", false, 60_000, { it }) { error("duplicate") } }
        waiter.cancelAndJoin()
        assertEquals(true, c.run("b", true, 60_000, { it }) { true })
        finish.complete(Unit); assertEquals(true, owner.await())
    }
    @Test fun failureDelayIsCappedAtOneHour() = runBlocking {
        var time = 0L
        val c = SubscriptionRefreshCoordinator<Boolean> { time }
        repeat(10) {
            assertEquals(false, c.run("a", false, 60_000, { it }) { false })
        }
        time = 3_599_999; assertNull(c.run("a", true, 60_000, { it }) { true })
        time = 3_600_000; assertEquals(true, c.run("a", true, 60_000, { it }) { true })
    }
}
