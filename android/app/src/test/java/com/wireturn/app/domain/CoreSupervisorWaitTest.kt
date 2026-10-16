package com.wireturn.app.domain

import com.wireturn.app.CaptchaSession
import com.wireturn.app.CoreStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class CoreSupervisorWaitTest {
    private val parked = listOf(CoreStatus.Suppressed, CoreStatus.WaitingForNetwork,
        CoreStatus.CaptchaRequired(CaptchaSession("https://example.invalid/captcha", 1)))

    // Run continuations synchronously and record timer requests without wall-clock sleeps.
    // A polling implementation would schedule a timer as soon as it sees a parked state.
    @OptIn(InternalCoroutinesApi::class)
    private class TimerRecorder : CoroutineDispatcher(), Delay {
        var timers = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) { timers++ }
    }

    @Test fun everyParkedStateWaitsForAnEventWithoutSchedulingTicks() = runBlocking {
        for (initial in parked) {
            val status = MutableStateFlow<CoreStatus>(initial)
            val dispatcher = TimerRecorder()
            val waiter = launch(dispatcher) { awaitCoreSupervisorResume(status) }
            assertEquals(1, status.subscriptionCount.value)
            assertFalse(waiter.isCompleted)
            assertEquals(0, dispatcher.timers)
            for (anotherParkedState in parked) {
                status.value = anotherParkedState
                assertFalse(waiter.isCompleted)
                assertEquals(0, dispatcher.timers)
            }
            status.value = CoreStatus.Connecting
            waiter.join()
            assertEquals(0, status.subscriptionCount.value)
            assertEquals(0, dispatcher.timers)
        }
    }

    @Test fun transitionBeforeSubscriptionIsNotLost() = runBlocking {
        val status = MutableStateFlow<CoreStatus>(CoreStatus.WaitingForNetwork)
        status.value = CoreStatus.Connecting
        withTimeout(1000) { awaitCoreSupervisorResume(status) }
        assertEquals(0, status.subscriptionCount.value)
    }

    @Test fun cancellationWhileParkedUnsubscribesAndNeverContinues() = runBlocking {
        for (initial in parked) {
            val status = MutableStateFlow<CoreStatus>(initial)
            var continued = false
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                awaitCoreSupervisorResume(status)
                continued = true
            }
            assertEquals(1, status.subscriptionCount.value)
            waiter.cancelAndJoin()
            status.value = CoreStatus.Connecting
            assertTrue(waiter.isCancelled)
            assertFalse(continued)
            assertEquals(0, status.subscriptionCount.value)
        }
    }

    @Test fun stopTransitionReleasesWaitButStopGuardPreventsRestart() = runBlocking {
        val status = MutableStateFlow<CoreStatus>(CoreStatus.Suppressed)
        var stopped = false
        var starts = 0
        val supervisor = launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive && !stopped) {
                awaitCoreSupervisorResume(status)
                if (!isActive || stopped) break
                starts++
            }
        }
        stopped = true
        status.value = CoreStatus.Stopping
        supervisor.join()
        assertEquals(0, starts)
        assertEquals(0, status.subscriptionCount.value)
    }
}
