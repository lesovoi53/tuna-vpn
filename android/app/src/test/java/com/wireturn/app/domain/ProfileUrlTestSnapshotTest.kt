package com.wireturn.app.domain

import com.google.gson.JsonObject
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnOptions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProfileUrlTestSnapshotTest {
    private fun profile(id: String) = Profile(id, "VPN $id", KernelConfig.Vpn(VpnConfig(
        "vless://00000000-0000-0000-0000-000000000001@192.0.2.1:9443?security=none")))

    private class Scenario(initial: List<Profile>) {
        val batch = ProfileUrlTestBatch(parallelism = 1)
        var current = initial
        fun update(profiles: List<Profile>) { current = profiles; batch.profilesChanged(profiles) }
        fun label(profile: Profile): String? = batch.state.value.labelFor(profile)
        suspend fun run(probe: suspend (Profile) -> Long) {
            batch.run("sub", current, { current }, probe = probe)
        }
    }

    @Test fun pendingProfileReclassifiedToBypassIsNotStarted() = runBlocking {
        val a = profile("a")
        val b = profile("b")
        val scenario = Scenario(listOf(a, b))
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val visited = mutableListOf<String>()
        val job = launch {
            scenario.run {
                visited += it.id
                if (it.id == a.id) { entered.complete(Unit); resume.await() }
                42L
            }
        }
        entered.await()
        val renamed = b.copy(name = "Москва обход бс")
        scenario.update(listOf(a, renamed))
        resume.complete(Unit)
        job.join()
        assertEquals(listOf("a"), visited)
        assertNull(scenario.label(renamed))
    }

    @Test fun completedResultCannotBeDisplayedForSameIdReplacementOrRename() = runBlocking {
        val old = profile("a")
        val scenario = Scenario(listOf(old))
        scenario.run { 42L }
        assertEquals("42 мс", scenario.label(old))
        val edited = old.copy(kernelConfig = KernelConfig.Vpn(VpnConfig("trojan://test@192.0.2.2:9443")))
        // The UI must be safe even before the profile-flow invalidation observer runs.
        assertNull(scenario.label(edited))
        assertNull(scenario.label(old.copy(name = "Renamed VPN")))
        scenario.update(listOf(edited))
        assertNull(scenario.label(edited))
        assertTrue(scenario.batch.state.value.results.isEmpty())
    }

    @Test fun completedResultIsRemovedAfterDeletionAndDoesNotResurrect() = runBlocking {
        val old = profile("a")
        val scenario = Scenario(listOf(old))
        scenario.run { 42L }
        scenario.update(emptyList())
        assertTrue(scenario.batch.state.value.results.isEmpty())
        scenario.update(listOf(old.copy()))
        assertNull(scenario.label(old))
    }

    @Test fun pendingConfigReplacementRenameAndDeletionAreSkippedEvenBeforeObserverRuns() = runBlocking {
        val a = profile("a")
        val b = profile("b").copy(subscriptionId = "sub", subscriptionSourceId = "remote-b")
        val replacements = listOf(
            b.copy(name = "Renamed VPN"),
            b.copy(kernelConfig = KernelConfig.Vpn(VpnConfig("trojan://test@192.0.2.2:9443"))),
            b.copy(subscriptionSourceId = "replacement-b"),
            null
        )
        for (replacement in replacements) {
            val scenario = Scenario(listOf(a, b))
            val visited = mutableListOf<String>()
            scenario.run {
                visited += it.id
                // This simulates a subscription refresh ahead of the flow collector.
                scenario.current = listOfNotNull(a, replacement)
                42L
            }
            assertEquals(listOf("a"), visited)
            assertFalse(scenario.batch.state.value.results.containsKey("b"))
            assertEquals(2, scenario.batch.state.value.completed)
        }
    }

    @Test fun runningReclassifiedReplacedOrDeletedMemberIsCancelledAndCleanedBeforeContinuing() = runBlocking {
        val a = profile("a")
        val b = profile("b")
        val replacements = listOf(a.copy(name = "Москва обход бс"),
            a.copy(kernelConfig = KernelConfig.Vpn(VpnConfig("trojan://test@192.0.2.2:9443"))), null)
        for (replacement in replacements) {
            val scenario = Scenario(listOf(a, b))
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            val visited = mutableListOf<String>()
            val job = launch {
                scenario.run {
                    visited += it.id
                    if (it.id == "a") {
                        try { entered.complete(Unit); awaitCancellation() }
                        finally { withContext(NonCancellable) { yield(); cleaned = true } }
                    }
                    assertTrue("The next member must wait for cancelled tunnel cleanup", cleaned)
                    7L
                }
            }
            entered.await()
            scenario.update(listOfNotNull(replacement, b))
            job.join()
            assertFalse(job.isCancelled)
            assertEquals(listOf("a", "b"), visited)
            assertTrue(cleaned)
            assertFalse(scenario.batch.state.value.results.containsKey("a"))
            assertEquals("7 мс", scenario.label(b))
            assertEquals(2, scenario.batch.state.value.completed)
        }
    }

    @Test fun changeBeforePublicationCannotPublishOldSuccessWithoutAnObserver() = runBlocking {
        val a = profile("a")
        val scenario = Scenario(listOf(a))
        scenario.run {
            scenario.current = listOf(a.copy(name = "New name"))
            42L
        }
        assertTrue(scenario.batch.state.value.results.isEmpty())
    }

    @Test fun mutableNativeOptionsAreFrozenAndInPlaceEditsInvalidateResult() = runBlocking {
        val patch = JsonObject().apply { addProperty("testOption", "before") }
        val a = profile("a").copy(kernelConfig = KernelConfig.Vpn(VpnConfig("trojan://test@192.0.2.1:9443",
            options = VpnOptions(xray = patch))))
        val scenario = Scenario(listOf(a))
        scenario.run { captured ->
            patch.addProperty("testOption", "after")
            assertEquals("before", (captured.kernelConfig as KernelConfig.Vpn).config.options!!.xray!!.get("testOption").asString)
            42L
        }
        assertTrue(scenario.batch.state.value.results.isEmpty())
        scenario.run { 7L }
        patch.addProperty("testOption", "edited after completion")
        assertNull(scenario.label(a))
        scenario.update(listOf(a))
        assertTrue(scenario.batch.state.value.results.isEmpty())
    }

    @Test fun staleCandidatesAndBypassCoresAreSkippedButBypassNamedVlessIsTested() = runBlocking {
        val a = profile("a")
        val bypass = profile("b").copy(name = "Обход БС")
        val qwdtt = Profile("qwdtt", "qWDTT", KernelConfig.Qwdtt())
        val batch = ProfileUrlTestBatch(parallelism = 1)
        val current = listOf(a.copy(name = "Changed"), bypass, qwdtt)
        val visited = mutableListOf<String>()
        batch.run("sub", listOf(a, bypass, qwdtt), { current }) { visited += it.id; 42L }
        assertEquals(listOf(bypass.id), visited)
        assertEquals(setOf(bypass.id), batch.state.value.results.keys)
        assertEquals("42 мс", batch.state.value.labelFor(bypass))
        assertFalse(batch.state.value.busy)
    }

    @Test fun completedBypassVlessResultSurvivesUnchangedRefreshButNotProtocolReplacement() = runBlocking {
        val original = profile("b").copy(name = "Обход БС [CF]", subscriptionId = "sub", subscriptionSourceId = "remote-b")
        val scenario = Scenario(listOf(original))
        scenario.run { 23L }
        assertEquals("23 мс", scenario.label(original))
        scenario.update(listOf(original.copy()))
        assertEquals("23 мс", scenario.label(original))
        val changed = original.copy(kernelConfig = KernelConfig.Vpn(VpnConfig("trojan://test@192.0.2.1:9443")))
        assertNull(scenario.label(changed))
        scenario.update(listOf(changed))
        assertTrue(scenario.batch.state.value.results.isEmpty())
    }

    @Test fun anUnchangedSubscriptionRefreshKeepsTheMatchingCompletedResult() = runBlocking {
        val a = profile("a").copy(subscriptionId = "sub", subscriptionSourceId = "remote-a")
        val scenario = Scenario(listOf(a))
        scenario.run { 42L }
        scenario.update(listOf(a.copy()))
        assertEquals("42 мс", scenario.label(a))
    }
}
