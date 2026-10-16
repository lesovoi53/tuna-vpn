package com.wireturn.app.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** JNI ownership. Call start/stop under the service's native mutex, including TUN cleanup. */
internal class NativeTunnelRuntime(
    private val startNative: (String, Int) -> Boolean,
    private val stopNative: () -> Unit
) {
    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    suspend fun start(config: String, fd: Int): Boolean {
        currentCoroutineContext().ensureActive()
        // JNI cannot be cancelled. Record ownership before the dispatcher hands control
        // back to a cancelled caller, otherwise Stop cannot see the live native tunnel.
        return withContext(NonCancellable + Dispatchers.IO) {
            startNative(config, fd).also { running.set(it) }
        }
    }

    fun stop(): Boolean {
        if (!running.get()) return false
        stopNative()
        running.set(false)
        return true
    }
}
