package com.wireturn.app.kernel

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Return only after the old child has exited, so a replacement cannot reuse its live sockets. */
internal suspend fun terminateCoreProcess(
    process: Process,
    terminate: (Process) -> Unit,
    graceMillis: Long = 5_000,
    forceMillis: Long = 2_000,
    forceTerminate: (Process) -> Unit = { it.destroyForcibly() }
) = withContext(NonCancellable + Dispatchers.IO) {
    try {
        if (process.isAlive) {
            val signalled = runCatching { terminate(process) }.isSuccess
            if (!signalled || !process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                forceTerminate(process)
                check(process.waitFor(forceMillis, TimeUnit.MILLISECONDS)) {
                    "Core process did not exit after forced termination"
                }
            }
        }
    } finally {
        if (!process.isAlive) {
            // signalAndroidProcess deliberately avoids destroy() while stdout is blocked.
            // Once reaped, release the unused pipes explicitly, including natural exits.
            // The output pump owns stdout until its final diagnostic has been drained.
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
