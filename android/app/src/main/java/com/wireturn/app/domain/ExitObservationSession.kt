package com.wireturn.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged

/** Visibility gates initial work; hiding the screen is not a change of egress. */
internal fun exitDiagnosticSessions(visible: Flow<Boolean>, connection: Flow<Long?>): Flow<Long> =
    homeDiagnosticStates(visible, connection.filterNotNull()).filterNotNull().distinctUntilChanged()

/** A service generation is process-local, so this cache intentionally is not restored across process death. */
object ExitObservationSession {
    private var generation: Long? = null
    private var requestId = 0L
    private val mutable = MutableStateFlow(ExitObservationState())
    val state = mutable.asStateFlow()
    @Synchronized fun needsRefresh(key: Long): Boolean = generation != key ||
        (!mutable.value.loading && mutable.value.observation == null && !mutable.value.unavailable)
    @Synchronized fun begin(key: Long): Long {
        val old = mutable.value.takeIf { generation == key } ?: ExitObservationState()
        generation = key
        mutable.value = old.copy(loading = true, unavailable = false)
        return ++requestId
    }
    @Synchronized fun complete(key: Long, value: ExitObservation?, token: Long = requestId) {
        if (generation == key && token == requestId) mutable.value = mutable.value.copy(loading = false,
            observation = value ?: mutable.value.observation, unavailable = value == null)
    }
    @Synchronized fun cancel(key: Long, token: Long = requestId) {
        if (generation == key && token == requestId) mutable.value = mutable.value.copy(loading = false)
    }
}
