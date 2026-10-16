package com.wireturn.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

/** Process-wide single flight. Waiting callers cannot cancel the owning request. */
internal class SubscriptionRefreshCoordinator<T>(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Retry(val failures: Int, val completedAt: Long, val retryAt: Long = Long.MIN_VALUE) {
        fun next(successIntervalMs: Long): Long = if (failures == 0)
            completedAt + successIntervalMs.coerceAtLeast(60_000) else retryAt
    }
    private val lock = Any()
    private val running = mutableMapOf<String, CompletableDeferred<T>>()
    private val retries = mutableMapOf<String, Retry>()

    suspend fun run(key: String, automatic: Boolean, successIntervalMs: Long,
                    successful: (T) -> Boolean, action: suspend () -> T): T? {
        var owner = false
        val result = synchronized(lock) {
            running[key] ?: run {
                // Re-evaluate successful refreshes against the current persisted settings.
                // First import may receive its interval inside action; later callers see it.
                if (automatic && now() < (retries[key]?.next(successIntervalMs) ?: Long.MIN_VALUE)) return null
                owner = true
                CompletableDeferred<T>().also { running[key] = it }
            }
        }
        if (!owner) return result.await()
        try {
            val value = action()
            synchronized(lock) {
                val failures = if (successful(value)) 0 else ((retries[key]?.failures ?: 0) + 1).coerceAtMost(7)
                val completedAt = now()
                val retryAt = if (failures == 0) Long.MIN_VALUE else
                    completedAt + (60_000L * (1L shl (failures - 1))).coerceAtMost(3_600_000)
                retries[key] = Retry(failures, completedAt, retryAt)
            }
            result.complete(value)
            return value
        } catch (cancelled: CancellationException) {
            result.cancel(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lock) { running.remove(key) }
        }
    }
}

internal val subscriptionRefreshCoordinator = SubscriptionRefreshCoordinator<ImportStatus>()
