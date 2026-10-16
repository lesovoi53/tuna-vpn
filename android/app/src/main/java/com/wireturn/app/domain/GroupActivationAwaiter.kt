package com.wireturn.app.domain

import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.ClientConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal suspend fun awaitGroupActivation(
    profileId: String,
    before: CoreServiceState.RunningSession?,
    sessions: StateFlow<CoreServiceState.RunningSession?>,
    status: StateFlow<CoreStatus>,
    timeoutMs: Long = 45_000L,
    start: suspend () -> Unit
): ClientConfig = withTimeout(timeoutMs) {
    // Starting is transient and StateFlow may conflate it with Connected. A new,
    // matching session is the durable completion marker; another profile is not.
    val initialStatus = status.value
    start()
    combine(sessions, status) { session, state -> session to state }
        .first { (session, state) ->
            // Validation/startup can fail before CoreService publishes its new session.
            // Ignore a stale initial error and failures belonging to an unrelated switch.
            if (state is CoreStatus.Error && state !== initialStatus &&
                (session == null || session === before || session.profileId == profileId)) {
                error("Не удалось подключить выбранный сервер")
            }
            if (session == null || session === before || session.profileId != profileId) false
            else {
                check(state !is CoreStatus.Error) { "Не удалось подключить выбранный сервер" }
                state == CoreStatus.Connected
            }
        }.first!!.clientConfig
}
