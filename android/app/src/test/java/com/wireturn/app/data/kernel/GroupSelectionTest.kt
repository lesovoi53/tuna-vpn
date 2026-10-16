package com.wireturn.app.data.kernel

import com.wireturn.app.data.*
import com.wireturn.app.domain.GroupMeasurement
import com.wireturn.app.domain.GroupSelection
import org.junit.Assert.*
import org.junit.Test

class GroupSelectionTest {
    @Test fun urlSelectionIgnoresLegacySpeedValues() {
        val results = listOf(sample("a", 10, speed = Double.NaN), sample("b", 30, speed = Double.POSITIVE_INFINITY))
        assertEquals("a", GroupSelection().choose(group, results, null, 100_000))
    }
    @Test fun failedActiveRespectsHoldAndNeverSwitchesOnSingleFailure() {
        val selector = GroupSelection()
        val protected = group.copy(confirmations = 1, holdSeconds = 30)
        assertEquals("a", selector.choose(protected, listOf(sample("a", 10)), null, 100_000))
        val onlyB = listOf(sample("b", 20))
        assertEquals("a", selector.choose(protected, onlyB, "a", 101_000))
        assertEquals("a", selector.choose(protected, onlyB, "a", 102_000))
        assertEquals("b", selector.choose(protected, onlyB, "a", 131_000))
    }
    private val group = ConnectionGroup("g", "Test", ConnectionCategory.VPN, GroupTestType.URL_TEST,
        listOf("a", "b"), holdSeconds = 10, confirmations = 2, improvementMs = 20)
    private fun sample(id: String, latency: Long, time: Long = 100_000, speed: Double = 0.0) =
        GroupMeasurement(id, latency, speed, time)

    @Test fun initialSelectionAndRepeatedImprovementRespectHold() {
        val selector = GroupSelection()
        assertEquals("a", selector.choose(group, listOf(sample("a", 20), sample("b", 100)), null, 100_000))
        val improved = listOf(sample("a", 100), sample("b", 20))
        assertEquals("a", selector.choose(group, improved, "a", 101_000))
        assertEquals("a", selector.choose(group, improved, "a", 102_000))
        assertEquals("b", selector.choose(group, improved, "a", 111_000))
    }
    @Test fun slightImprovementAndTieDoNotSwitch() {
        val selector = GroupSelection()
        repeat(4) { assertEquals("a", selector.choose(group, listOf(sample("a", 100), sample("b", 85)), "a", 100_000)) }
        assertEquals("a", selector.choose(group, listOf(sample("a", 100), sample("b", 100)), "a", 100_000))
    }
    @Test fun failureNeedsConfirmationAndAllFailedHasNoWinner() {
        val selector = GroupSelection()
        val onlyB = listOf(sample("b", 40))
        assertEquals("a", selector.choose(group, onlyB, "a", 100_000))
        assertEquals("b", selector.choose(group, onlyB, "a", 100_001))
        assertNull(selector.choose(group, emptyList(), "b", 100_002))
    }
    @Test fun staleForeignAndFailedResultsExcluded() {
        val selector = GroupSelection()
        val results = listOf(sample("other", 1), sample("a", 1, 0), sample("b", 1).copy(error = "failed"))
        assertNull(selector.choose(group.copy(freshnessSeconds = 30), results, null, 100_000))
    }
    @Test fun legacySpeedGroupsNeverSelectAWinner() {
        val legacy = group.copy(type = GroupTestType.SPEEDTEST, automatic = true)
        val results = listOf(sample("a", 10, speed = 100.0), sample("b", 30, speed = 200.0))
        assertNull(GroupSelection().choose(legacy, results, null, 100_000))
        assertNull(GroupSelection().choose(legacy, results, "a", 100_000))
    }
    @Test fun invalidTestConfigurationRejected() {
        val profiles = listOf(Profile("a", "A", KernelConfig.Vpn(VpnConfig("snell://secret@example.com:443?version=4"))),
            Profile("b", "B", KernelConfig.Vpn(VpnConfig("snell://secret@example.org:443?version=4"))))
        group.validate(profiles)
        listOf(group.copy(testUrl = "http://example.com"), group.copy(samples = 0),
            group.copy(routingProfileId = "missing")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { bad.validate(profiles) }
        }
        assertThrows(IllegalArgumentException::class.java) { group.validate(profiles.map { if (it.id == "b") Profile("b", "Bypass", KernelConfig.Qwdtt()) else it }) }
    }
}
