package com.wireturn.app.domain

import com.google.gson.Gson
import com.wireturn.app.data.Profile
import org.junit.Assert.*
import org.junit.Test

class ProbeHistoryTest {
    private val profile = Profile("one", "Example")
    private fun result(kind: ProbeKind = ProbeKind.URL, time: Long = 100, group: String? = null) =
        SavedProbeResult(profile.id, kind, probeConfigDigest(profile), time, latencyMs = 42, groupId = group)
    @Test fun roundTripPreservesBothTypesAndScope() {
        var history = mergeProbeHistory(emptyList(), result())
        history = mergeProbeHistory(history, result(ProbeKind.SPEED))
        history = mergeProbeHistory(history, result(group = "group"))
        val decoded = Gson().fromJson(Gson().toJson(history), Array<SavedProbeResult>::class.java).toList()
        assertEquals(3, decoded.size)
        assertNotNull(decoded.latestProbe(profile, ProbeKind.SPEED))
        assertEquals("group", decoded.latestProbe(profile, ProbeKind.URL, "group")?.groupId)
    }
    @Test fun renameDoesNotInvalidateButConnectionChangeDoes() {
        assertFalse(result().isStale(profile.copy(name = "Renamed")))
        assertTrue(result().isStale(profile.copy(xrayEnabled = true)))
    }
    @Test fun latestFailureReplacesSuccessWithoutInventingZeroLatency() {
        val failed = result(time = 200).copy(latencyMs = null, error = "Тайм-аут")
        val history = mergeProbeHistory(listOf(result()), failed)
        assertNull(history.single().latencyMs)
        assertEquals("Тайм-аут", history.single().error)
        assertEquals(history, mergeProbeHistory(history, result(time = 50)))
    }
    @Test fun limitsStorageAndDoesNotPersistConfiguration() {
        val history = (1..10).fold(emptyList<SavedProbeResult>()) { acc, i -> mergeProbeHistory(acc, result(time = i.toLong()).copy(profileId = "$i"), 3) }
        assertEquals(3, history.size)
        val json = Gson().toJson(history)
        assertFalse(json.contains("kernelConfig"))
        assertTrue(history.all { it.configDigest.length == 64 })
    }
    @Test fun changingGlobalFragmentationInvalidatesSavedAndInFlightResults() {
        val before = ProbeHistoryEnvironment.fragmentation
        try {
            val saved = result()
            val snapshot = ProfileUrlTestSnapshot.capture(profile)
            ProbeHistoryEnvironment.fragmentation = "{mode:both}"
            assertTrue(saved.isStale(profile))
            assertEquals(saved.configDigest, snapshot.configDigest)
            assertNotEquals(snapshot.configDigest, probeConfigDigest(profile))
        } finally { ProbeHistoryEnvironment.fragmentation = before }
    }
}
