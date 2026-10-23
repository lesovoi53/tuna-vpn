package com.wireturn.app.domain

import com.wireturn.app.data.*
import org.junit.Assert.*
import org.junit.Test

class GroupSelectionExplanationTest {
    private val group = ConnectionGroup("g", "Snell", ConnectionCategory.VPN, GroupTestType.URL_TEST,
        listOf("msk", "spb"), confirmations = 2, holdSeconds = 120)
    private fun sample(id: String, ms: Long, time: Long) = GroupMeasurement(id, ms, measuredAt = time)

    @Test fun screenshotLatenciesWaitForConfirmationThenSwitch() {
        val s = GroupSelection()
        assertEquals("msk", s.choose(group, listOf(sample("msk", 400, 100_000), sample("spb", 500, 100_000)), null, 100_000))
        val results = listOf(sample("msk", 719, 400_000), sample("spb", 433, 400_000))
        assertEquals("msk", s.choose(group, results, "msk", 400_000))
        assertTrue(s.decisionMessage.contains("1 из 2"))
        assertEquals("spb", s.choose(group, results.map { it.copy(measuredAt = 700_000) }, "msk", 700_000))
        assertTrue(s.decisionMessage.contains("подтверждённого"))
    }

    @Test fun initialConnectionChoosesFastestWithoutWaiting() {
        val s = GroupSelection()
        assertEquals("spb", s.choose(group, listOf(sample("msk", 719, 100_000), sample("spb", 433, 100_000)), null, 100_000))
        assertTrue(s.decisionMessage.contains("минимальной"))
    }

    @Test fun priorityAndHoldHaveDistinctReasons() {
        val s = GroupSelection()
        val samples = listOf(sample("msk", 719, 100_000), sample("spb", 433, 100_000))
        assertEquals("msk", s.choose(group.copy(selectionMode = GroupSelectionMode.PRIORITY), samples, "msk", 100_000))
        assertTrue(s.decisionMessage.contains("приоритету"))
        s.choose(group, listOf(sample("msk", 400, 100_000)), null, 100_000)
        s.choose(group.copy(confirmations = 1), samples, "msk", 110_000)
        assertTrue(s.decisionMessage.contains("удержания"))
    }
}
