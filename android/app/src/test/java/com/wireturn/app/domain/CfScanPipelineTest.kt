package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CfScanPipelineTest {
    @Test fun fullFiveThousandCandidateScanHasExactCountsAndBoundedVisibleCache() = runBlocking {
        val inputs = (1..5000).map(Int::toString)
        val seen = mutableSetOf<String>()
        var visible = emptyList<CfResult>()
        var finalProgress: CfScanProgress? = null
        val tcpBusy = java.util.concurrent.atomic.AtomicInteger()
        val nativeBusy = java.util.concurrent.atomic.AtomicInteger()
        val maxTcp = java.util.concurrent.atomic.AtomicInteger()
        val maxNative = java.util.concurrent.atomic.AtomicInteger()
        withContext(Dispatchers.Default) {
            val outcome = CfScanPipeline { 30_000L }.run(inputs,
                CfSettings(count = 5000, quick = false, tcpParallelism = 32, parallelism = 4),
                tcp = { ip ->
                    maxTcp.accumulateAndGet(tcpBusy.incrementAndGet(), ::maxOf)
                    try { yield(); ip.toInt() % 2 == 0 } finally { tcpBusy.decrementAndGet() }
                }, probe = { ip, _ ->
                    maxNative.accumulateAndGet(nativeBusy.incrementAndGet(), ::maxOf)
                    try { yield(); good(ip) } finally { nativeBusy.decrementAndGet() }
                }, onResult = {
                    seen += it.ip
                    visible = CfResultCache.visible(visible, it)
                    assertTrue(visible.size <= CfResultCache.LIMIT)
                }, onProgress = { progress ->
                    finalProgress?.let { previous ->
                        assertTrue(progress.tcpChecked >= previous.tcpChecked)
                        assertTrue(progress.completed >= previous.completed)
                    }
                    finalProgress = progress
                })
            assertFalse(outcome.timedOut)
        }
        assertEquals(inputs.toSet(), seen)
        assertEquals(5000, finalProgress!!.tcpChecked)
        assertEquals(2500, finalProgress!!.profileChecked)
        assertEquals(5000, finalProgress!!.completed)
        assertEquals(2500, finalProgress!!.confirmed)
        assertEquals(CfResultCache.LIMIT, visible.size)
        assertTrue(maxTcp.get() in 1..32)
        assertTrue(maxNative.get() in 1..4)
        assertEquals(0, tcpBusy.get())
        assertEquals(0, nativeBusy.get())
    }

    @Test fun cancellationOfFullRecheckCannotLaunchAnotherCore() = runBlocking {
        repeat(50) {
            val started = CompletableDeferred<Unit>()
            val rechecked = mutableListOf<String>()
            val job = launch(Dispatchers.Unconfined) {
                CfScanPipeline().run(listOf("a", "b", "c"), CfSettings(quick = false, parallelism = 1),
                    tcp = { true }, probe = { ip, stable ->
                        if (stable) { rechecked += ip; started.complete(Unit); awaitCancellation() }
                        good(ip)
                    }, onResult = {})
            }
            started.await()
            job.cancelAndJoin()
            assertEquals(listOf("a"), rechecked)
        }
    }
    private fun good(ip: String, ms: Long = 10) = CfResult(ip, listOf(ms, ms + 1, ms + 2), 3)
    @Test fun rejectedFirstHttpsAttemptDoesNotWasteRemainingAttempts() = runBlocking {
        var calls = 0
        val failed = CfProbeSeries.measure(3) { calls++; throw IOException("unavailable") }
        assertEquals(1, calls)
        assertEquals(1, failed.attempts)
        assertTrue(failed.times.isEmpty())
        calls = 0
        val success = CfProbeSeries.measure(3) { calls++; 42L }
        assertEquals(3, calls)
        assertEquals(listOf(42L, 42L, 42L), success.times)
    }
    @Test fun singleSampleTimeoutDoesNotCancelTheWholeScan() = runBlocking {
        var calls = 0
        val result = CfProbeSeries.measure(3) {
            calls++
            withTimeout(5) { awaitCancellation() }
        }
        assertEquals(1, calls)
        assertEquals(1, result.attempts)
    }
    @Test fun quickGoalRequiresFreshSessionAndStopsUnneededNativeWork() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val saved = mutableListOf<CfResult>()
        val outcome = CfScanPipeline().run((1..32).map { "$it" }, CfSettings(parallelism = 1, target = 1),
            tcp = { true }, probe = { ip, stable -> calls += ip to stable; good(ip) },
            onResult = { saved += it })
        assertTrue(outcome.reachedTarget)
        assertFalse(outcome.timedOut)
        assertEquals(listOf("1" to false, "1" to true), calls)
        assertEquals(1, saved.count { it.usable })
        assertEquals(6, saved.single().attempts)
    }
    @Test fun badFreshSessionDoesNotCountTowardQuickGoal() = runBlocking {
        val saved = mutableListOf<CfResult>()
        val outcome = CfScanPipeline().run(listOf("a", "b"), CfSettings(parallelism = 1, target = 1),
            tcp = { true }, probe = { ip, stable ->
                if (ip == "a" && stable) CfResult(ip, emptyList(), 1, "HTTPS") else good(ip)
            }, onResult = { saved += it })
        assertTrue(outcome.reachedTarget)
        assertFalse(saved.first().usable)
        assertTrue(saved.last().usable)
        assertEquals("b", saved.last().ip)
    }
    @Test fun closedAddressesNeverLaunchAProfileCore() = runBlocking {
        var native = 0
        val saved = mutableListOf<CfResult>()
        val outcome = CfScanPipeline().run((1..20).map { "$it" }, CfSettings(),
            tcp = { false }, probe = { ip, _ -> native++; good(ip) }, onResult = { saved += it })
        assertEquals(0, native)
        assertEquals(20, saved.size)
        assertTrue(saved.none { it.usable })
        assertFalse(outcome.reachedTarget)
    }
    @Test fun cheapAndNativeWorkHaveIndependentBounds() = runBlocking {
        var tcp = 0; var native = 0; var maxTcp = 0; var maxNative = 0
        val allTcpStarted = CompletableDeferred<Unit>()
        val settings = CfSettings(tcpParallelism = 4, parallelism = 2, quick = false)
        withTimeout(3000) {
            CfScanPipeline().run((1..12).map { "$it" }, settings, tcp = {
                tcp++; maxTcp = maxOf(maxTcp, tcp)
                if (tcp == 4) allTcpStarted.complete(Unit)
                try { allTcpStarted.await(); delay(2); true } finally { tcp-- }
            }, probe = { ip, _ ->
                native++; maxNative = maxOf(maxNative, native)
                try { delay(5); good(ip) } finally { native-- }
            }, onResult = {})
        }
        assertEquals(4, maxTcp)
        assertEquals(2, maxNative)
        assertEquals(0, tcp)
        assertEquals(0, native)
    }
    @Test fun fullModeRechecksOnlyTheBestThreeWithFreshSessions() = runBlocking {
        val rechecks = mutableListOf<String>()
        CfScanPipeline().run((1..5).map { "$it" }, CfSettings(quick = false),
            tcp = { true }, probe = { ip, stable ->
                if (stable) rechecks += ip
                good(ip, ip.toLong())
            }, onResult = {})
        assertEquals(setOf("1", "2", "3"), rechecks.toSet())
        assertEquals(3, rechecks.size)
    }
    @Test fun cancelKeepsPublishedResultsAndJoinsRunningProbeCleanup() = runBlocking {
        val published = CompletableDeferred<Unit>()
        val inSlowProbe = CompletableDeferred<Unit>()
        var cleaned = false
        var cache = emptyList<CfResult>()
        val job = launch {
            CfScanPipeline().run(listOf("working", "slow"), CfSettings(parallelism = 1, quick = false),
                tcp = { true }, probe = { ip, _ ->
                    if (ip == "slow") {
                        inSlowProbe.complete(Unit)
                        try { awaitCancellation() } finally { cleaned = true }
                    }
                    good(ip)
                }, onResult = {
                    cache = CfResultCache.merge(cache, listOf(it))
                    published.complete(Unit)
                })
        }
        withTimeout(3000) { published.await(); inSlowProbe.await() }
        job.cancelAndJoin()
        assertTrue(cleaned)
        assertEquals(listOf("working"), cache.map { it.ip })
    }
    @Test fun timeBudgetReturnsAfterCleanupWithoutInventingResults() = runBlocking {
        var cleaned = false
        val outcome = CfScanPipeline { 30L }.run(listOf("slow"), CfSettings(),
            tcp = { true }, probe = { _, _ ->
                try { awaitCancellation() } finally { cleaned = true }
            }, onResult = { fail("An unfinished probe is not a result") })
        assertTrue(outcome.timedOut)
        assertTrue(cleaned)
    }
    @Test fun invalidatedScanCanNeverWriteEvenIfNetworkChangesBack() {
        val ticket = CfScanToken("profile-v1", "network-1")
        assertTrue(ticket.accepts("profile-v1", "network-1"))
        assertFalse(ticket.accepts("profile-v2", "network-1"))
        assertFalse(ticket.accepts("profile-v1", "network-2"))
        ticket.invalidate()
        assertFalse(ticket.accepts("profile-v1", "network-1"))
    }
    @Test fun publishedResultCannotBePersistedAfterProfileOrNetworkInvalidation() = runBlocking {
        val ticket = CfScanToken("v1", "wifi")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val persisted = mutableListOf<CfResult>()
        val job = launch {
            CfScanPipeline().run(listOf("working"), CfSettings(quick = false), tcp = { true },
                probe = { ip, _ ->
                    entered.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                    good(ip)
                }, onResult = { if (ticket.accepts("v1", "wifi")) persisted += it })
        }
        entered.await()
        ticket.invalidate()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertTrue(persisted.isEmpty())
    }
    @Test fun outerCancellationIsNotReportedAsInternalTimeBudget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var reportedSuccess = false
        val job = launch {
            CfScanPipeline().run(listOf("slow"), CfSettings(), tcp = { true },
                probe = { _, _ -> entered.complete(Unit); awaitCancellation() }, onResult = {})
            reportedSuccess = true
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(reportedSuccess)
    }
    @Test fun cacheKeepsUntestedAddressesButRemovesFailedRetests() {
        val old = listOf(good("old"), good("failed"))
        val update = listOf(good("new"), CfResult("failed", emptyList(), 1, "HTTPS"))
        assertEquals(setOf("old", "new"), CfResultCache.merge(old, update).map { it.ip }.toSet())
        assertFalse(CfResult("cleanup-error", listOf(1, 2), 2, "HTTPS").usable)
    }
}
