package com.wireturn.app.kernel

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.ensureActive
import com.wireturn.app.CoreStatus
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState

/** A separate handle per operation: cancelling an old run cannot release a new run's lock. */
internal suspend fun <T> withWakeLease(acquire: () -> AutoCloseable, block: suspend () -> T): T {
    val lease = acquire()
    try { return block() } finally { lease.close() }
}

internal fun connectionWorkPending(core: CoreStatus, xray: XrayState, vpn: VpnState): Boolean {
    if (core is CoreStatus.Idle || core is CoreStatus.Stopping || core is CoreStatus.WaitingForNetwork ||
        core is CoreStatus.Error || core is CoreStatus.CaptchaRequired) return false
    return core is CoreStatus.Starting || core is CoreStatus.Connecting ||
        xray is XrayState.Starting || xray is XrayState.Connecting || vpn is VpnState.Starting
}

internal object OperationWakeLock {
    /** Explicit opt-in only. Bounded OS lease, renewed while requested. */
    suspend fun holdUserRequested(context: Context): Nothing {
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TUNA::user-enabled")
        lock.setReferenceCounted(false)
        try {
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lock.acquire(600_000)
                kotlinx.coroutines.delay(540_000)
            }
        } finally { if (lock.isHeld) lock.release() }
    }
    suspend fun <T> run(context: Context, name: String, timeoutMs: Long = 120_000, block: suspend () -> T): T =
        withWakeLease(acquire = {
            require(timeoutMs in 1..120_000)
            val lock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TUNA::${name.take(32)}")
            lock.setReferenceCounted(false)
            lock.acquire(timeoutMs)
            AutoCloseable { if (lock.isHeld) lock.release() }
        }, block = block)
}

internal fun userWakeRequested(enabled: Boolean, core: CoreStatus, vpn: VpnState): Boolean =
    enabled && vpn == VpnState.Running && (core == CoreStatus.Connected || core == CoreStatus.Suppressed)
