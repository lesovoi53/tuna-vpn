package com.wireturn.app.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

/** Owns only the minute timer. Fetches started by [tick] belong to the manager's scope. */
internal suspend fun runSubscriptionUpdateSchedule(
    enabled: Flow<Boolean>,
    wait: suspend (Long) -> Unit = { delay(it) },
    tick: () -> Unit
) {
    enabled.distinctUntilChanged().collectLatest { active ->
        if (active) while (currentCoroutineContext().isActive) {
            // Enabling starts a fresh minute; disabled time never queues catch-up work.
            wait(60_000L)
            currentCoroutineContext().ensureActive()
            tick()
        }
    }
}
