package com.wireturn.app.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CfScanProgress(val tcpChecked: Int, val profileChecked: Int, val completed: Int,
    val confirmed: Int, val total: Int, val stability: Boolean = false)
data class CfScanOutcome(val reachedTarget: Boolean, val timedOut: Boolean)

/** Independent lightweight and native queues. Callbacks are serialized, including cancellation cleanup. */
class CfScanPipeline(private val budgetMillis: (CfSettings) -> Long = { it.budgetSeconds * 1000L }) {
    suspend fun run(candidates: List<String>, settings: CfSettings,
        tcp: suspend (String) -> Boolean,
        probe: suspend (String, Boolean) -> CfResult,
        onResult: suspend (CfResult) -> Unit,
        onProgress: suspend (CfScanProgress) -> Unit = {},
    ): CfScanOutcome = coroutineScope {
        val ips = candidates.distinct()
        val results = linkedMapOf<String, CfResult>()
        val mutex = Mutex()
        val targetReached = CompletableDeferred<Unit>()
        var tcpChecked = 0
        var profileChecked = 0
        var confirmed = 0
        suspend fun progress(stability: Boolean = false) = onProgress(CfScanProgress(
            tcpChecked, profileChecked, results.size, confirmed, ips.size, stability))
        suspend fun publish(result: CfResult, native: Boolean, stability: Boolean = false) {
            // Do not discard a finished sample when the user stops while its callback is queued.
            // The owner still validates the profile and network before persisting it.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (native && !stability) profileChecked++
                    val previous = results.put(result.ip, result)
                    confirmed += (if (result.usable) 1 else 0) - (if (previous?.usable == true) 1 else 0)
                    onResult(result)
                    progress(stability)
                    if (settings.quick && confirmed >= settings.target) targetReached.complete(Unit)
                }
            }
        }
        suspend fun safeProbe(ip: String, stable: Boolean): CfResult = try { probe(ip, stable) }
        catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); CfResult(ip, emptyList(), 0, "HTTPS") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { CfResult(ip, emptyList(), 0, "HTTPS") }
        fun merge(first: CfResult, second: CfResult) = first.copy(times = first.times + second.times,
            attempts = first.attempts + second.attempts, failedStage = if (second.usable) null else second.failedStage ?: "HTTPS")
        val finished = withTimeoutOrNull(budgetMillis(settings)) {
            coroutineScope {
                val work = launch {
                    coroutineScope {
                        val pending = Channel<String>(settings.tcpParallelism)
                        val ready = Channel<String>(settings.tcpParallelism * 2)
                        launch { try { ips.forEach { pending.send(it) } } finally { pending.close() } }
                        val tcpWorkers = List(settings.tcpParallelism) {
                            launch {
                                for (ip in pending) {
                                    ensureActive()
                                    val available = try { tcp(ip) }
                                    catch (_: TimeoutCancellationException) { ensureActive(); false }
                                    catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { false }
                                    mutex.withLock { tcpChecked++; progress() }
                                    if (available) ready.send(ip)
                                    else publish(CfResult(ip, emptyList(), 0, "TCP"), native = false)
                                }
                            }
                        }
                        launch { try { tcpWorkers.joinAll() } finally { ready.close() } }
                        repeat(settings.parallelism) {
                            launch {
                                for (ip in ready) {
                                    ensureActive()
                                    val first = safeProbe(ip, false)
                                    // Quick mode confirms a fresh session before counting this IP toward the goal.
                                    val result = if (settings.quick && first.usable) {
                                        mutex.withLock { progress(stability = true) }
                                        merge(first, safeProbe(ip, true))
                                    } else first
                                    publish(result, native = true)
                                    if (targetReached.isCompleted) break
                                }
                            }
                        }
                    }
                }
                val stopper = launch { targetReached.await(); work.cancel() }
                try { work.join() }
                finally { withContext(NonCancellable) { work.cancelAndJoin(); stopper.cancelAndJoin() } }
            }
            if (!settings.quick) {
                // Full mode preserves the existing fresh-session recheck of the best three only.
                val best = results.values.filter { it.usable }.sortedWith(CfResult.order).take(3)
                coroutineScope {
                    val recheckContext = currentCoroutineContext()
                    val slots = kotlinx.coroutines.sync.Semaphore(settings.parallelism)
                    best.map { first -> launch {
                        slots.acquire()
                        try {
                            // A cancelled sibling can release a permit before cancellation
                            // reaches this job; never start a core after its owner has stopped.
                            recheckContext.ensureActive()
                            ensureActive()
                            publish(merge(first, safeProbe(first.ip, true)), native = false, stability = true)
                        }
                        finally { slots.release() }
                    } }.joinAll()
                }
            }
            true
        } ?: false
        CfScanOutcome(targetReached.isCompleted, !finished)
    }
}

/** A cancelled network/profile generation can never become valid again by changing back. */
class CfScanToken(val profileSnapshot: String, val networkStamp: String) {
    @Volatile private var valid = true
    fun invalidate() { valid = false }
    fun accepts(profile: String?, network: String?) = valid && profile == profileSnapshot && network == networkStamp
}

object CfResultCache {
    const val LIMIT = 256
    fun visible(previous: List<CfResult>, result: CfResult): List<CfResult> =
        (previous.filterNot { it.ip == result.ip } + result).sortedWith(CfResult.order).take(LIMIT)
    fun merge(previous: List<CfResult>, measured: List<CfResult>): List<CfResult> =
        (previous.associateBy { it.ip } + measured.associateBy { it.ip }).values
            .filter { it.usable }.sortedWith(CfResult.order).take(LIMIT)
}

/** A failed sample cannot meet the all-samples-successful criterion; stop wasting further attempts. */
object CfProbeSeries {
    data class Result(val times: List<Long>, val attempts: Int)
    suspend fun measure(samples: Int, check: suspend () -> Long): Result {
        val times = mutableListOf<Long>()
        var attempts = 0
        repeat(samples) {
            currentCoroutineContext().ensureActive()
            attempts++
            try { times += check() }
            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); return Result(times, attempts) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return Result(times, attempts) }
        }
        return Result(times, attempts)
    }
}
