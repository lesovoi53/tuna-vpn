package com.wireturn.app.domain

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class TunMtuPolicyTest {
    @Test fun defaultAndCorruptPreferencesRetainIpv6SafeDefault() {
        val prefs = mutablePreferencesOf()
        assertEquals(1280, TunMtuPolicy.read(prefs))
        for (invalid in listOf(-1, 0, 1279, TunMtuPolicy.MAX + 1, Int.MAX_VALUE)) {
            prefs[intPreferencesKey("vpn_tun_mtu")] = invalid
            assertEquals(1280, TunMtuPolicy.read(prefs))
            assertNull(TunMtuPolicy.parse(invalid.toString()))
            assertTrue(runCatching { TunMtuPolicy.write(prefs, invalid) }.isFailure)
        }
        listOf("", "1.5", "65536", "999999999999").forEach { assertNull(TunMtuPolicy.parse(it)) }
    }
    @Test fun fullSliderRangeAndManualInputReachEveryIntegerIncludingMaximum() {
        for (mtu in TunMtuPolicy.MIN..TunMtuPolicy.MAX) {
            assertEquals(mtu, TunMtuPolicy.sliderValue(TunMtuPolicy.sliderFraction(mtu)))
            assertEquals(mtu, TunMtuPolicy.parse(mtu.toString()))
        }
        assertEquals(TunMtuPolicy.MIN, TunMtuPolicy.sliderValue(-1f))
        assertEquals(TunMtuPolicy.MAX, TunMtuPolicy.sliderValue(2f))
    }
    @Test fun activeSnapshotSurvivesPreferenceEditsUntilNextConnection() {
        val prefs = mutablePreferencesOf()
        TunMtuPolicy.write(prefs, 1500)
        val active = TunMtuPolicy.Snapshot(TunMtuPolicy.read(prefs))
        TunMtuPolicy.write(prefs, TunMtuPolicy.MAX)
        assertEquals(1500, active.mtu)
        assertEquals(TunMtuPolicy.MAX, TunMtuPolicy.Snapshot(TunMtuPolicy.read(prefs)).mtu)
        assertTrue(runCatching { TunMtuPolicy.Snapshot(1279) }.isFailure)
    }
}
