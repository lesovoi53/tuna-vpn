package com.wireturn.app.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ExitObservationSessionTest {
    @Test fun reopeningHomeDoesNotProduceNewEgressRequest() = runBlocking {
        val visible = MutableStateFlow(true)
        val session = MutableStateFlow<Long?>(10)
        val seen = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            scope.launch { exitDiagnosticSessions(visible, session).collect { seen += it } }
            repeat(3) { visible.value = false; visible.value = true }
            assertEquals(listOf(10L), seen)
            visible.value = false; session.value = 11; visible.value = true
            assertEquals(listOf(10L, 11L), seen)
        } finally { scope.cancel() }
    }
    @Test fun manualRefreshKeepsPreviousAddressUntilReplaced() {
        val key = 123_456L
        val observed = ExitObservation("203.0.113.1", "DE", false, 50)
        ExitObservationSession.begin(key); ExitObservationSession.complete(key, observed)
        assertFalse(ExitObservationSession.needsRefresh(key))
        ExitObservationSession.begin(key)
        assertEquals(observed, ExitObservationSession.state.value.observation)
        ExitObservationSession.complete(key, null)
        assertEquals(observed, ExitObservationSession.state.value.observation)
        assertTrue(ExitObservationSession.state.value.unavailable)
        assertTrue(ExitObservationSession.needsRefresh(key + 1))
    }
    @Test fun canceledOldRequestCannotClearNewLoadingOrPublishOldAddress() {
        val key = 555_321L
        val old = ExitObservationSession.begin(key)
        val current = ExitObservationSession.begin(key)
        ExitObservationSession.cancel(key, old)
        assertTrue(ExitObservationSession.state.value.loading)
        ExitObservationSession.complete(key, ExitObservation("203.0.113.1", null, null, 1), old)
        assertTrue(ExitObservationSession.state.value.loading)
        ExitObservationSession.complete(key, ExitObservation("203.0.113.2", null, null, 2), current)
        assertEquals("203.0.113.2", ExitObservationSession.state.value.observation?.ip)
    }
}
