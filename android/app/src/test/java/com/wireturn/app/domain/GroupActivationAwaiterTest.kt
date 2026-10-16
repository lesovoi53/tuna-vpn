package com.wireturn.app.domain

import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.ClientConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class GroupActivationAwaiterTest {
    private fun session(id: String) = CoreServiceState.RunningSession(ClientConfig(), id, startedAtElapsed = 0, profileId = id)

    @Test fun startupErrorBeforeTargetSessionFailsWithoutWaitingForDeadline() = runBlocking {
        val before = session("old")
        val sessions = MutableStateFlow<CoreServiceState.RunningSession?>(before)
        val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
        try {
            awaitGroupActivation("target", before, sessions, status, 150) {
                status.value = CoreStatus.Error("private startup diagnostic")
            }
            fail("Startup failed before target session was published")
        } catch (error: IllegalStateException) {
            assertFalse("Must report startup failure before the deadline", error is TimeoutCancellationException)
            assertFalse(error.message.orEmpty().contains("private startup diagnostic"))
        }
    }

    @Test fun staleInitialErrorDoesNotRejectPendingValidStartup() = runBlocking {
        val before = session("old")
        val sessions = MutableStateFlow<CoreServiceState.RunningSession?>(before)
        val status = MutableStateFlow<CoreStatus>(CoreStatus.Error("old attempt"))
        val target = session("target")
        val result = awaitGroupActivation("target", before, sessions, status, 1000) {
            launch {
                yield()
                sessions.value = target
                status.value = CoreStatus.Connected
            }
        }
        assertEquals(target.clientConfig, result)
    }

    @Test fun failedMatchingSessionFailsWithoutWaitingForDeadline() = runBlocking {
        val before = session("old")
        val sessions = MutableStateFlow<CoreServiceState.RunningSession?>(before)
        val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
        try {
            awaitGroupActivation("target", before, sessions, status, 150) {
                sessions.value = session("target")
                status.value = CoreStatus.Error("private diagnostic")
            }
            fail("Failed target must fail promptly")
        } catch (error: IllegalStateException) {
            assertFalse(error.message.orEmpty().contains("private diagnostic"))
        }
    }

    @Test fun fastStartingTransitionCannotStrandConnectedTarget() = runBlocking {
        val before = session("old")
        val sessions = MutableStateFlow<CoreServiceState.RunningSession?>(before)
        val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
        val target = session("target")
        val result = awaitGroupActivation("target", before, sessions, status, 150) {
            // StateFlow conflates these changes before the waiting coroutine resumes.
            status.value = CoreStatus.Starting
            sessions.value = target
            status.value = CoreStatus.Connected
        }
        assertEquals(target.clientConfig, result)
    }

    @Test fun unrelatedConnectedSessionCannotCompleteActivation() = runBlocking {
        val before = session("old")
        val sessions = MutableStateFlow<CoreServiceState.RunningSession?>(before)
        val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
        val result = async {
            awaitGroupActivation("target", before, sessions, status, 1000) {
                status.value = CoreStatus.Starting
                yield()
                sessions.value = session("unrelated")
                status.value = CoreStatus.Connected
            }
        }
        repeat(5) { yield() }
        assertFalse("Another profile cannot satisfy this switch", result.isCompleted)
        sessions.value = session("target")
        result.await()
        Unit
    }
}
