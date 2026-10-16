package com.wireturn.app.kernel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Commands are submitted on the service's main thread. */
internal class CoreCommandLifecycle(
    private val scope: CoroutineScope,
    private val commandDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val generation = AtomicLong()
    var job: Job? = null
        private set

    /** Publishing Starting wakes parked supervisors; retire their authority first.
     * Keep the job reference so start() still joins its non-cancellable cleanup. */
    fun cancelBeforeStart(publishStarting: () -> Unit) {
        generation.incrementAndGet()
        job?.cancel()
        publishStarting()
    }

    fun start(stopProcess: suspend () -> Unit, run: suspend CoroutineScope.() -> Unit) {
        generation.incrementAndGet()
        job = scope.replaceCoreRun(job, stopProcess, run)
    }

    fun stop(stopProcess: suspend () -> Unit, cleanup: suspend () -> Unit, onStopped: suspend () -> Unit) {
        val request = generation.incrementAndGet()
        val previous = job
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // A subsequent Start cancels this job, but must still wait for all resource
            // cleanup. Capture the predecessor now, not later on an IO dispatcher.
            withContext(NonCancellable) {
                retireCoreRun(previous, stopProcess)
                cleanup()
                // Submission and completion share Main: the check and stopSelf cannot
                // straddle a newer command. Superseded cleanup must not publish Idle.
                withContext(commandDispatcher) {
                    if (generation.get() == request) onStopped()
                }
            }
        }
    }
}
