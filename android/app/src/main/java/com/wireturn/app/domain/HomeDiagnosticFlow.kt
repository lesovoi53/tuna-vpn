package com.wireturn.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

/** Diagnostic requests belong to the visible home screen, not every settings ViewModel. */
internal fun <T : Any> homeDiagnosticStates(visible: Flow<Boolean>, connection: Flow<T>): Flow<T?> =
    combine(visible, connection) { shown, state -> state.takeIf { shown } }.distinctUntilChanged()

/** No ticking job exists while hidden. Resuming performs one current sample, never catch-up. */
internal suspend fun pollWhileVisible(visible: Flow<Boolean>, intervalMs: Long = 1000,
    wait: suspend (Long) -> Unit = { delay(it) }, sample: suspend () -> Unit) {
    require(intervalMs > 0)
    visible.distinctUntilChanged().collectLatest { shown ->
        if (shown) while (currentCoroutineContext().isActive) {
            sample()
            wait(intervalMs)
        }
    }
}
