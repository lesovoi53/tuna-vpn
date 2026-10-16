package com.wireturn.app.domain

import com.wireturn.app.CoreStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

internal fun isCoreSupervisorParked(status: CoreStatus): Boolean =
    status is CoreStatus.Suppressed || status is CoreStatus.WaitingForNetwork || status is CoreStatus.CaptchaRequired

/** Park without timers until a supervisor, network callback or captcha handler changes state.
 * StateFlow immediately supplies its latest value, including a transition before subscription.
 * The service must recheck its stop flag after this wait before starting another process. */
internal suspend fun awaitCoreSupervisorResume(status: StateFlow<CoreStatus>) {
    currentCoroutineContext().ensureActive()
    status.first { !isCoreSupervisorParked(it) }
    currentCoroutineContext().ensureActive()
}
