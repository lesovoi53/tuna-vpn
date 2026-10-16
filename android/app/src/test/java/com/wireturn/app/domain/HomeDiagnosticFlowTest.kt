package com.wireturn.app.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class HomeDiagnosticFlowTest {
    @Test fun hiddenDisplayClockCancelsPendingTickAndResumesFromCurrentTime() = runBlocking {
        val visible = MutableStateFlow(true)
        var clock = 1000L
        val samples = mutableListOf<Long>()
        var pendingTicks = 0
        var cancelledTicks = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = scope.launch {
            pollWhileVisible(visible, wait = {
                pendingTicks++
                try { awaitCancellation() }
                finally { pendingTicks--; cancelledTicks++ }
            }) { samples += clock }
        }
        try {
            yield()
            assertEquals(listOf(1000L), samples)
            assertEquals(1, pendingTicks)
            visible.value = false
            yield()
            assertEquals("Hiding cancels the existing delay", 0, pendingTicks)
            assertEquals(1, cancelledTicks)
            clock = 61_000L
            yield()
            assertEquals("Hidden time cannot generate display updates", listOf(1000L), samples)
            visible.value = true
            yield()
            assertEquals("Resume samples the clock once without replaying missed seconds", listOf(1000L, 61_000L), samples)
            assertEquals(1, pendingTicks)
        } finally { job.cancelAndJoin(); scope.cancel() }
        assertEquals("Disposal also cancels the pending tick", 0, pendingTicks)
    }

    @Test fun hiddenMetricsHaveNoTimerAndResumeWithoutCatchup() = runBlocking {
        val visible = MutableStateFlow(false)
        var samples = 0
        var waits = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = scope.launch {
            pollWhileVisible(visible, wait = { waits++; awaitCancellation() }) { samples++ }
        }
        try {
            yield()
            assertEquals(0, waits)
            visible.value = true
            yield()
            assertEquals(1, samples)
            visible.value = false
            yield()
            assertEquals(1, waits)
            visible.value = true
            yield()
            assertEquals(2, samples)
            assertEquals(2, waits)
        } finally { job.cancelAndJoin(); scope.cancel() }
    }
    @Test fun openingSettingsCannotStartAnotherSetOfHomeDiagnostics() = runBlocking {
        val connection = MutableStateFlow("connected-1")
        val homeVisible = MutableStateFlow(true)
        val settingsVisible = MutableStateFlow(false)
        val home = mutableListOf<String?>()
        val settings = mutableListOf<String?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            scope.launch { homeDiagnosticStates(homeVisible, connection).collect { home += it } }
            scope.launch { homeDiagnosticStates(settingsVisible, connection).collect { settings += it } }
            assertEquals(listOf("connected-1"), home)
            assertEquals("A settings-only ViewModel has no home diagnostics owner", listOf<String?>(null), settings)
            homeVisible.value = false
            connection.value = "connected-2"
            assertNull("Hidden home cancels pending diagnostics", home.last())
            assertEquals(listOf<String?>(null), settings)
            homeVisible.value = true
            assertEquals(listOf("connected-1", null, "connected-2"), home)
        } finally { scope.cancel() }
    }
    @Test fun repeatedVisibilityAndConnectionEventsDoNotDuplicateRequests() = runBlocking {
        val visible = MutableSharedFlow<Boolean>(replay = 1)
        val connection = MutableSharedFlow<String>(replay = 1)
        val states = mutableListOf<String?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            scope.launch { homeDiagnosticStates(visible, connection).collect { states += it } }
            visible.emit(false); connection.emit("session")
            repeat(3) { visible.emit(false); connection.emit("session") }
            visible.emit(true);repeat(3) { visible.emit(true); connection.emit("session") }
            visible.emit(false)
            assertEquals(listOf(null, "session", null), states)
        } finally { scope.cancel() }
    }
}
