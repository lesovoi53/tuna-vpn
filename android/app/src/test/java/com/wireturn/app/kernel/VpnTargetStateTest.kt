package com.wireturn.app.kernel

import org.junit.Assert.*
import org.junit.Test

class VpnTargetStateTest {
    private data class Target(val address: String, val password: String)

    @Test fun retainedTunNeedsNewTargetAfterUpstreamGap() {
        val state = VpnTargetState<Target>()
        val old = Target("127.0.0.1:10808", "old-test-password")
        val replacement = Target("127.0.0.1:10809", "new-test-password")
        state.markRequested(old)
        // No request is sent to HEV while upstream availability is null.
        assertTrue(state.needsUpdate(replacement))
        state.markRequested(replacement)
        assertFalse(state.needsUpdate(replacement))
    }

    @Test fun observingNewCredentialsDuringStartingDoesNotConsumeTheirUpdate() {
        val state = VpnTargetState<Target>()
        val old = Target("127.0.0.1:10808", "old-test-password")
        val replacement = old.copy(password = "new-test-password")
        state.markRequested(old)
        assertTrue(state.needsUpdate(replacement)) // Starting: no retarget can be sent yet.
        assertTrue(state.needsUpdate(replacement)) // Running: reconcile the pending change.
        state.markRequested(replacement)
        assertFalse(state.needsUpdate(replacement))
    }

    @Test fun reattachedSupervisorReconcilesAnAlreadyRunningVpn() {
        val state = VpnTargetState<Target>()
        assertTrue(state.needsUpdate(Target("127.0.0.1:10808", "test-password")))
    }
}
