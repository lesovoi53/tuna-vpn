package com.wireturn.app.kernel

import com.wireturn.app.CoreServiceState.RunningSession
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.ClientConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RunningSessionLifecycleTest {
    @Test fun sameConfigurationRestartPublishesNewConnectedSession() = runBlocking {
        val before = RunningSession(ClientConfig(), "same profile", startedAtElapsed = 100)
        val session = MutableStateFlow<RunningSession?>(before)
        val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
        val connected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(200) {
                combine(session, status) { current, state -> current to state }
                    .first { (current, state) -> current != null && current !== before && state == CoreStatus.Connected }
                    .first
            }
        }
        status.value = CoreStatus.Starting
        val restarted = RunningSession(before.clientConfig, before.profileName, startedAtElapsed = 200)
        session.value = restarted
        status.value = CoreStatus.Connected
        assertSame("A same-config restart must not be conflated with the previous run", restarted, connected.await())
    }
}
