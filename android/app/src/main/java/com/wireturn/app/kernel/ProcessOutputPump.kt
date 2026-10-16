package com.wireturn.app.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keep the output pipe open while a failed core releases its connections on SIGTERM. */
internal suspend fun drainProcessOutput(
    process: Process,
    terminate: (Process) -> Unit,
    graceMillis: Long = 5_000,
    stopOnCancellation: (suspend () -> Unit)? = null,
    onLine: suspend (String) -> Boolean
) = coroutineScope {
    val ownerContext = coroutineContext
    val finished = AtomicBoolean()
    // Opt-in draining must have a bounded process stop independent of the blocked reader.
    // This also handles owner cancellation caused by a sibling coroutine's failure.
    val cancellationStopper = stopOnCancellation?.let { stop ->
        launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                if (!finished.get()) withContext(NonCancellable + Dispatchers.IO) { stop() }
            }
        }
    }
    try {
        val readContext = if (stopOnCancellation != null) NonCancellable + Dispatchers.IO else Dispatchers.IO
        withContext(readContext) {
            coroutineScope {
                var stopping = false
                var deadline: Job? = null
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (ownerContext.isActive || stopOnCancellation != null) {
                            val line = reader.readLine() ?: break
                            val shouldStop = if (stopping || !ownerContext.isActive) false else try {
                                // A suspended parser must remain cancellable; only raw draining survives.
                                withContext(ownerContext) { onLine(line) }
                            } catch (cancelled: CancellationException) {
                                if (ownerContext.isActive) throw cancelled
                                false
                            }
                            if (shouldStop) {
                                stopping = true
                                terminate(process)
                                deadline = launch {
                                    delay(graceMillis)
                                    if (process.isAlive) process.destroyForcibly()
                                }
                            }
                        }
                    }
                    // EOF does not necessarily mean the process has exited. Keep the deadline active.
                    if (stopping && !process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(graceMillis, TimeUnit.MILLISECONDS)
                    }
                } finally {
                    deadline?.cancel()
                }
            }
        }
    } finally {
        finished.set(true)
        withContext(NonCancellable) { cancellationStopper?.cancelAndJoin() }
    }
}
