package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServerAccessProbeTest {
    @Test fun parentDeadlineStillStopsWithoutTryingTheFallback() {
        var calls = 0
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(40L) {
                    ServerAccessProbe.check { calls++; withTimeout(5000L) { awaitCancellation() } }
                }
            }
        }
        assertEquals(1, calls)
    }

    @Test fun firstEndpointTimeoutStillTriesTheSecond() = runBlocking {
        var calls = 0
        assertTrue(ServerAccessProbe.check {
            if (++calls == 1) withTimeout(20L) { awaitCancellation() }
        })
        assertEquals(2, calls)
    }

    @Test fun twoEndpointTimeoutsAreAnInconclusiveResultAndNextCheckCanRun() = runBlocking {
        var calls = 0
        assertFalse(ServerAccessProbe.check { calls++; withTimeout(20L) { awaitCancellation() } })
        assertEquals(2, calls)
        assertTrue(ServerAccessProbe.check { })
    }

    @Test fun stoppingTheSessionCancelsWithoutFallback() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val job = launch { ServerAccessProbe.check { calls++; started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun ordinaryFailureFallsBackAndSuccessDoesNotProbeAgain() = runBlocking {
        var calls = 0
        assertTrue(ServerAccessProbe.check { if (++calls == 1) error("Unavailable") })
        assertEquals(2, calls)
        calls = 0
        assertTrue(ServerAccessProbe.check { calls++ })
        assertEquals(1, calls)
    }
}
