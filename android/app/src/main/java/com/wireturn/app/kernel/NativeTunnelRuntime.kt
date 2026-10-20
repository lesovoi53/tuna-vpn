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
    private var configuration: AutoCloseable? = null
    val isRunning: Boolean get() = running.get()

    suspend fun start(config: String, fd: Int, resource: AutoCloseable? = null): Boolean {
        try {
            currentCoroutineContext().ensureActive()
            check(!running.get() && configuration == null) { "Native tunnel still owns its previous configuration" }
        } catch (failure: Throwable) {
            resource?.close()
            throw failure
        }
        // JNI cannot be cancelled. Record ownership before the dispatcher hands control
        // back to a cancelled caller, otherwise Stop cannot see the live native tunnel.
        return withContext(NonCancellable + Dispatchers.IO) {
            configuration = resource
            // If JNI throws after partial startup, retain both ownership and the memfd
            // until stopNative succeeds. A returned false explicitly means no owner.
            running.set(true)
            startNative(config, fd).also {
                running.set(it)
                if (!it) releaseConfiguration()
            }
        }
    }

    fun stop(): Boolean {
        val wasRunning = running.get()
        if (wasRunning) {
            stopNative()
            running.set(false)
        }
        releaseConfiguration()
        return wasRunning
    }

    private fun releaseConfiguration() {
        configuration?.close()
        configuration = null
    }
}
