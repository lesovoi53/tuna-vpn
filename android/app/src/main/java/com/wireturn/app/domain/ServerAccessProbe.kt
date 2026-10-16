package com.wireturn.app.domain

import kotlinx.coroutines.*

/** A failed endpoint is inconclusive; a second independent endpoint may still work. */
internal object ServerAccessProbe {
    suspend fun check(measure: suspend (String) -> Unit): Boolean {
        for (url in listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
            try {
                measure(url)
                return true
            } catch (_: TimeoutCancellationException) {
                // The endpoint's own deadline is a failed measurement. A parent
                // session cancellation must still stop immediately.
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        return false
    }
}
