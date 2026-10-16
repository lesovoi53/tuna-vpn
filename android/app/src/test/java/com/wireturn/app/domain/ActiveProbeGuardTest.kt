package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ActiveProbeGuardTest {
    @Test fun disconnectRacingSocketFailureIsCancellationNotSavedFailure() {
        assertThrows(CancellationException::class.java) { runBlocking {
            var current = true
            withActiveProbeSession({ current }) {
                current = false
                throw javax.net.ssl.SSLException("peer text must not become a saved error after stop")
            }
        } }
    }
    @Test fun changedSessionCannotReturnSuccessfulResult() {
        assertThrows(CancellationException::class.java) { runBlocking {
            var current = true
            withActiveProbeSession({ current }) { current = false; 42 }
        } }
    }
    @Test fun sameSessionRetainsRealFailure() {
        assertThrows(java.io.IOException::class.java) { runBlocking {
            withActiveProbeSession({ true }) { throw java.io.IOException("real failure") }
        } }
    }
    @Test fun disconnectCancelsAndJoinsBlockedMeasurement() = runBlocking {
        var current = true
        var cleaned = false
        val started = CompletableDeferred<Unit>()
        val result = async {
            withActiveProbeSession({ current }) {
                try { started.complete(Unit); awaitCancellation() } finally { cleaned = true }
            }
        }
        started.await(); current = false
        try { withTimeout(1000) { result.await() }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertTrue(cleaned)
    }
}
