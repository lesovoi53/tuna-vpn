package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkStateTest {
    @Test fun filteredAvailableDoesNotDependOnRacingSynchronousCapabilitiesQuery() {
        val state = PhysicalNetworkState()
        // Android has matched INTERNET + NOT_VPN, but getNetworkCapabilities
        // may still return null inside onAvailable. Its callback arrives next.
        state.available(1, null)
        state.capabilities(1, true)
        assertTrue("working physical network was lost from recovery state", state.hasNetwork())
    }

    @Test fun removedInternetCapabilityCannotKeepRecoveryOnline() {
        val state = PhysicalNetworkState()
        state.available(1, true)
        state.capabilities(1, false)
        assertFalse("ineligible network still counts as available", state.hasNetwork())
    }

    @Test fun firstNetworkAndRepeatedAvailabilityDoNotRestartTheCore() {
        val state = PhysicalNetworkState()
        assertEquals(PhysicalNetworkState.Change.FIRST, state.available(1, true))
        assertEquals(PhysicalNetworkState.Change.NONE, state.available(1, true))
        assertEquals(PhysicalNetworkState.Change.CHANGED, state.available(2, true))
        state.lost(1)
        assertTrue(state.hasNetwork())
        state.lost(2)
        assertFalse(state.hasNetwork())
    }

    @Test fun staleCallbacksCannotResurrectLostOrPreviousNetworks() {
        val state = PhysicalNetworkState()
        state.available(1)
        state.available(2)
        assertFalse(state.capabilities(1, false))
        assertTrue(state.hasNetwork())
        state.lost(1)
        assertTrue(state.hasNetwork())
        state.lost(2)
        assertFalse(state.capabilities(2, true))
        assertFalse(state.hasNetwork())
        assertEquals(PhysicalNetworkState.Change.CHANGED, state.available(2))
    }

    @Test fun callbackRegistrationResetDoesNotInheritRestartState() {
        val state = PhysicalNetworkState()
        state.available(1)
        state.clear()
        assertFalse(state.hasNetwork())
        assertEquals(PhysicalNetworkState.Change.FIRST, state.available(2))
        assertEquals(PhysicalNetworkState.Change.NONE, state.available(3, false))
        assertTrue(state.hasNetwork())
    }
}
