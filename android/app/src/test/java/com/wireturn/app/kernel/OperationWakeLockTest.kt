package com.wireturn.app.kernel

import com.wireturn.app.CoreStatus
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OperationWakeLockTest {
    @Test fun manualWakeLockRequiresExplicitOptInAndActiveVpn() {
        assertFalse(userWakeRequested(false, CoreStatus.Connected, VpnState.Running))
        assertTrue(userWakeRequested(true, CoreStatus.Connected, VpnState.Running))
        assertFalse(userWakeRequested(true, CoreStatus.Connected, VpnState.Idle))
        for (state in listOf(CoreStatus.Stopping, CoreStatus.Idle, CoreStatus.WaitingForNetwork, CoreStatus.Error("test")))
            assertFalse(userWakeRequested(true, state, VpnState.Running))
    }
    @Test fun stableVpnAndOfflineWaitingNeverRequireConnectionLock() {
        assertFalse(connectionWorkPending(CoreStatus.Connected, XrayState.Running, VpnState.Running))
        assertFalse(connectionWorkPending(CoreStatus.Suppressed, XrayState.DirectRoute, VpnState.Running))
        for (state in listOf(CoreStatus.Idle, CoreStatus.Stopping, CoreStatus.WaitingForNetwork, CoreStatus.Error("test")))
            assertFalse(connectionWorkPending(state, XrayState.Connecting, VpnState.Starting))
        assertTrue(connectionWorkPending(CoreStatus.Connecting, XrayState.Idle, VpnState.Idle))
        assertTrue(connectionWorkPending(CoreStatus.Connected, XrayState.Starting, VpnState.Running))
        assertTrue(connectionWorkPending(CoreStatus.Connected, XrayState.Running, VpnState.Starting))
    }

    @Test fun cancellationOfOldOperationDoesNotReleaseNewOperation() = runBlocking {
        val held = mutableSetOf<Int>()
        fun acquire(id: Int): AutoCloseable { assertTrue(held.add(id)); return AutoCloseable { assertTrue(held.remove(id)) } }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { withWakeLease({ acquire(1) }) { awaitCancellation() } }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { withWakeLease({ acquire(2) }) { awaitCancellation() } }
        first.cancelAndJoin()
        assertEquals(setOf(2), held)
        second.cancelAndJoin()
        assertTrue(held.isEmpty())
        try { withWakeLease({ acquire(3) }) { error("test failure") } } catch (_: IllegalStateException) { }
        assertTrue(held.isEmpty())
    }
}
