package com.wireturn.app.ui.screens

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.domain.ProbeKind
import com.wireturn.app.domain.SavedProbeResult
import com.wireturn.app.domain.probeConfigDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedProbePresentationTest {
    private fun profile(id: String) = Profile(id = id, name = id,
        kernelConfig = KernelConfig.Vpn(VpnConfig("vless://test@example.invalid:443")))
    private fun result(profile: Profile, latency: Long?, error: String? = null, at: Long = 1L) =
        SavedProbeResult(profile.id, ProbeKind.URL, probeConfigDigest(profile), at, latencyMs = latency, error = error)

    @Test fun latencySortKeepsUntestedAndErrorsLastWithoutChangingSource() {
        val slow = profile("slow"); val failed = profile("failed"); val fast = profile("fast"); val unknown = profile("unknown")
        val source = listOf(slow, failed, fast, unknown)
        val history = listOf(result(slow, 100), result(failed, null, "Таймаут"), result(fast, 20))
        assertEquals(listOf(fast, slow, failed, unknown), filterAndSortProbedProfiles(source, history, false, true))
        assertEquals(listOf(slow, failed, fast, unknown), source)
    }

    @Test fun successesFilterExcludesChangedConfigurationsAndLatestFailure() {
        val ok = profile("ok"); val changed = profile("changed"); val failed = profile("failed")
        val modified = changed.copy(kernelConfig = KernelConfig.Vpn(VpnConfig("vless://test@changed.invalid:443")))
        val history = listOf(result(ok, 15), result(changed, 10), result(failed, 9), result(failed, null, "Таймаут", 2))
        assertEquals(listOf(ok), filterAndSortProbedProfiles(listOf(modified, ok, failed), history, true, false))
    }

    @Test fun speedResultDoesNotCountAsSuccessfulUrlProbeAndTiesAreStable() {
        val a = profile("a"); val b = profile("b"); val speedOnly = profile("speed")
        val history = listOf(result(a, 10), result(b, 10),
            SavedProbeResult(speedOnly.id, ProbeKind.SPEED, probeConfigDigest(speedOnly), 2, bytesPerSecond = 1000.0))
        assertEquals(listOf(b, a), filterAndSortProbedProfiles(listOf(b, speedOnly, a), history, true, true))
    }

    @Test fun disabledControlsPreserveOrderAndAllProfiles() {
        val source = listOf(profile("a"), profile("b"))
        assertEquals(source, filterAndSortProbedProfiles(source, emptyList(), false, false))
    }

    @Test fun relativeAgeUsesMinutesHoursDaysAndHandlesClockChanges() {
        val now = 200_000_000L
        assertEquals("только что", probeRelativeAge(now - 59_999, now))
        assertEquals("5 мин. назад", probeRelativeAge(now - 300_000, now))
        assertEquals("2 ч. назад", probeRelativeAge(now - 7_200_000, now))
        assertEquals("2 дн. назад", probeRelativeAge(now - 172_800_000, now))
        assertEquals("проверьте время устройства", probeRelativeAge(now + 120_000, now))
    }
}
