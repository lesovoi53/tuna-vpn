package com.wireturn.app.kernel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Replace the service's supervisor only after retiring its previous run. */
internal fun CoroutineScope.replaceCoreRun(
    previous: Job?,
    stopProcess: suspend () -> Unit,
    run: suspend CoroutineScope.() -> Unit
): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    // Enter retirement before another request can cancel this intermediate run. The next
    // replacement must transitively wait for every earlier run's cleanup before starting.
    retireCoreRun(previous, stopProcess)
    ensureActive()
    run()
}

internal suspend fun retireCoreRun(previous: Job?, stopProcess: suspend () -> Unit) {
    // Cancel before dispatching stop, so a superseded startup cannot race ahead. Joining first
    // deadlocks on a silent process's blocking stdout read; stop closes it while it is drained.
    previous?.cancel()
    withContext(NonCancellable + Dispatchers.IO) {
        stopProcess()
        previous?.join()
    }
}
