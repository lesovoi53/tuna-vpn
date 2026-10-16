package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ParallelUrlTestTest {
    private fun members() = (1..9).map { Profile("$it", "Server $it", KernelConfig.Vpn(
        VpnConfig("vless://test@192.0.2.$it:9443"))) }

    @Test fun fourConcurrentSlotsContinueAfterFailureWithoutLosingResults() = runBlocking {
        val batch = ProfileUrlTestBatch()
        val profiles = members()
        val firstWave = CompletableDeferred<Unit>()
        var active = 0; var peak = 0; var cleaned = 0
        batch.run("sub", profiles, { profiles }) {
            active++; peak = maxOf(peak, active)
            if (active == 4) firstWave.complete(Unit)
            try {
                withTimeout(2000) { firstWave.await() }
                delay(5)
                if (it.id == "2") error("failed endpoint")
                12L
            } finally { active--; cleaned++ }
        }
        assertEquals(4, peak)
        assertEquals(9, cleaned)
        assertEquals(9, batch.state.value.completed)
        assertEquals(8, batch.state.value.results.values.count { it.label == "12 мс" })
        assertEquals("Ошибка URL-теста", batch.state.value.results["2"]?.label)
    }

    @Test fun stopWaitsForAllActiveCleanupsAndNeverStartsQueuedProfiles() = runBlocking {
        val batch = ProfileUrlTestBatch()
        val profiles = members()
        val started = CompletableDeferred<Unit>()
        var active = 0; var cleaned = 0
        val job = launch { batch.run("sub", profiles, { profiles }) {
            if (++active == 4) started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(5); cleaned++ } }
        } }
        withTimeout(2000) { started.await() }
        job.cancelAndJoin()
        assertEquals(4, active)
        assertEquals(4, cleaned)
        assertFalse(batch.state.value.busy)
        assertTrue(batch.state.value.results.values.all { it.label == "Отменено" })
    }

    @Test fun editedMemberIsCancelledWithoutCancellingItsParallelNeighbour() = runBlocking {
        val batch = ProfileUrlTestBatch(parallelism = 2)
        var profiles = members().take(2)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch { batch.run("sub", profiles, { profiles }) {
            if (it.id == "1") {
                started.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
            finish.await(); 23L
        } }
        started.await()
        profiles = profiles.drop(1)
        batch.profilesChanged(profiles)
        finish.complete(Unit)
        job.join()
        assertTrue(cleaned)
        assertNull(batch.state.value.results["1"])
        assertEquals("23 мс", batch.state.value.results["2"]?.label)
    }

    @Test fun blockedFingerprintsAreReplacedOnImportAndExplicitSave() {
        for (fp in listOf("chrome", "random", "randomized")) {
            val config = VpnConfig("vless://test@example.com:443?security=tls&fp=$fp", "xray")
            assertTrue(VlessFingerprintChoices.effective(VlessFingerprintImport.forEditor(config, true)) in listOf("firefox", "safari"))
            assertTrue(VlessFingerprintChoices.effective(VlessFingerprintImport.forEditor(config, false)) in listOf("firefox", "safari"))
            assertNotNull(VlessFingerprintChoices.warning(config))
        }
        assertTrue(VlessFingerprintChoices.supported("xray").contains("hellochrome_133"))
        assertFalse(VlessFingerprintChoices.supported("sing-box").contains("hellochrome_133"))
    }
}
