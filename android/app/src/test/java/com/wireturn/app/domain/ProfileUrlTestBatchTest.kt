package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.VpnConfig

class ProfileUrlTestBatchTest {
    @Test fun explicitlyEligibleBypassIsMeasuredAndSavedButOtherBypassIsNot() = runBlocking {
        val active = Profile("active", "qWDTT", KernelConfig.Qwdtt(QwdttConfig()))
        val other = active.copy(id = "other")
        val saved = mutableListOf<ProfileUrlTestResult>()
        val batch = ProfileUrlTestBatch(onCompleted = saved::add)
        val visited = mutableListOf<String>()
        batch.run("manual", listOf(active, other), { listOf(active, other) },
            eligible = { it.id == active.id }) { visited += it.id; 42L }
        assertEquals(listOf("active"), visited)
        assertEquals("42 мс", batch.state.value.labelFor(active))
        assertEquals(1, saved.size)
        batch.profilesChanged(listOf(active, other))
        assertEquals("42 мс", batch.state.value.labelFor(active))
    }

    @Test fun activeBypassSnapshotRemainsCurrentForSavedMeasurements() {
        val profile = Profile("active-bypass", "qWDTT", KernelConfig.Qwdtt(QwdttConfig()))
        val snapshot = ProfileUrlTestSnapshot.capture(profile)
        assertTrue("Snapshot identity must not reject an unchanged bypass profile", snapshot.matches(profile))
        assertFalse(snapshot.matches(profile.copy(name = "changed")))
    }

    private suspend fun ProfileUrlTestBatch.runIds(owner: String, ids: List<String>, probe: suspend (String) -> Long) {
        val profiles = ids.distinct().map { Profile(it, it, KernelConfig.Vpn(VpnConfig("vless://test@192.0.2.1:9443"))) }
        run(owner, ids.map { id -> profiles.first { it.id == id } }, { profiles }) { probe(it.id) }
    }

    @Test fun innerReadinessTimeoutCompletesMemberAndContinuesAfterCleanup() = runBlocking {
        val batch = ProfileUrlTestBatch(parallelism = 1)
        val visited = mutableListOf<String>()
        var cleaned = false
        batch.runIds("sub", listOf("timeout", "working")) { id ->
            visited += id
            if (id == "timeout") {
                try { withTimeout(1) { awaitCancellation() } }
                finally { cleaned = true }
            }
            assertTrue("The next member must wait for tunnel cleanup", cleaned)
            42L
        }
        assertEquals(listOf("timeout", "working"), visited)
        assertEquals("Таймаут", batch.state.value.results["timeout"]?.label)
        assertEquals("42 мс", batch.state.value.results["working"]?.label)
        assertEquals(2, batch.state.value.completed)
        assertFalse(batch.state.value.busy)
    }

    @Test fun failureDoesNotDropRemainingServersAndDuplicatesRunOnce() = runBlocking {
        val batch = ProfileUrlTestBatch(parallelism = 1)
        val visited = mutableListOf<String>()
        batch.runIds("sub", listOf("a", "bad", "b", "a")) {
            visited += it
            if (it == "bad") error("secret credentials must not be displayed")
            42L
        }
        assertEquals(listOf("a", "bad", "b"), visited)
        assertEquals("42 мс", batch.state.value.results["b"]?.label)
        assertEquals("Ошибка URL-теста", batch.state.value.results["bad"]?.label)
        assertEquals(3, batch.state.value.completed)
        assertFalse(batch.state.value.busy)
    }

    @Test fun cancellationCleansUpAndDoesNotStartRemainingServers() = runBlocking {
        val batch = ProfileUrlTestBatch(parallelism = 1)
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch {
            batch.runIds("sub", listOf("a", "b")) {
                try { started.complete(Unit); awaitCancellation() }
                finally { cleaned = true }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(cleaned)
        assertFalse(batch.state.value.busy)
        assertEquals(setOf("Отменено"), batch.state.value.results.values.map { it.label }.toSet())
        batch.runIds("next", listOf("c")) { 7L }
        assertEquals("7 мс", batch.state.value.results["c"]?.label)
    }

    @Test fun memberDeadlineContinuesOnlyAfterCleanup() = runBlocking {
        val batch = ProfileUrlTestBatch(memberTimeoutMillis = 50, parallelism = 1)
        var cleaned = false
        val visited = mutableListOf<String>()
        batch.runIds("sub", listOf("slow", "working")) {
            visited += it
            if (it == "slow") {
                try { awaitCancellation() } finally { cleaned = true }
            }
            assertTrue(cleaned)
            7L
        }
        assertEquals(listOf("slow", "working"), visited)
        assertEquals("Таймаут", batch.state.value.results["slow"]?.label)
        assertEquals("7 мс", batch.state.value.results["working"]?.label)
        assertEquals(2, batch.state.value.completed)
    }

    @Test fun realOuterDeadlinePropagatesAndStopsTheQueue() = runBlocking {
        val batch = ProfileUrlTestBatch(parallelism = 1)
        var cleaned = false
        val visited = mutableListOf<String>()
        try {
            withTimeout(500) {
                batch.runIds("sub", listOf("a", "b")) {
                    visited += it
                    try { awaitCancellation() } finally { cleaned = true }
                }
            }
            fail("A deadline outside the batch must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertTrue(cleaned)
        assertEquals(listOf("a"), visited)
        assertEquals(0, batch.state.value.completed)
        assertEquals(setOf("Отменено"), batch.state.value.results.values.map { it.label }.toSet())
        assertFalse(batch.state.value.busy)
    }

    @Test fun synchronousCancellationCannotStartAWaitingSibling() = runBlocking {
        repeat(100) {
            val batch = ProfileUrlTestBatch(parallelism = 1)
            val visited = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()
            val owner = launch(Dispatchers.Unconfined) {
                batch.runIds("sub", listOf("a", "b", "c")) {
                    visited += it
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            owner.cancelAndJoin()
            assertEquals("A permit released during parent cancellation must not start a sibling", listOf("a"), visited)
            assertEquals(0, batch.state.value.completed)
            assertFalse(batch.state.value.busy)
        }
    }

    @Test fun userCancellationDuringInnerTimeoutCleanupCannotBeSwallowed() = runBlocking {
        val batch = ProfileUrlTestBatch(parallelism = 1)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val visited = mutableListOf<String>()
        var cleaned = false
        val job = launch {
            batch.runIds("sub", listOf("a", "b")) {
                visited += it
                try { withTimeout(1) { awaitCancellation() } }
                finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        releaseCleanup.await()
                        cleaned = true
                    }
                }
            }
        }
        cleanupEntered.await()
        job.cancel()
        releaseCleanup.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(cleaned)
        assertEquals(listOf("a"), visited)
        assertEquals(0, batch.state.value.completed)
        assertEquals(setOf("Отменено"), batch.state.value.results.values.map { it.label }.toSet())
        batch.runIds("next", listOf("b")) { 7L }
        assertEquals("7 мс", batch.state.value.results["b"]?.label)
    }

    @Test fun defaultsAre18ButExplicitAndLegacyWorkerCountsArePreserved() {
        assertEquals(18, QwdttConfig().effectiveWorkers)
        assertEquals(27, QwdttConfig(workers = 27).effectiveWorkers)
        val q = "qwdtt://config?peer=192.0.2.1%3A9000&pass=test&hashes=abcdefghijklmnop"
        assertEquals(18, QwdttConfig.parse(q)!!.effectiveWorkers)
        assertEquals(27, QwdttConfig.parse("$q&workers=27")!!.effectiveWorkers)
    }
}
