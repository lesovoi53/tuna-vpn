package com.wireturn.app.domain

import kotlinx.coroutines.*

/** Session identity is supplied by the Android caller; the lifetime contract is JVM-testable. */
internal suspend fun <T> withActiveProbeSession(isCurrent: () -> Boolean, block: suspend () -> T): T = supervisorScope {
    fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("Подключение изменилось во время теста")
    }
    requireCurrent()
    val measurement = async { block() }
    val guard = launch {
        while (isActive) {
            if (!isCurrent()) { measurement.cancel(CancellationException("Подключение изменилось во время теста")); break }
            delay(50)
        }
    }
    try {
        val result = measurement.await()
        requireCurrent()
        result
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        requireCurrent()
        throw failure
    } finally { guard.cancelAndJoin() }
}
