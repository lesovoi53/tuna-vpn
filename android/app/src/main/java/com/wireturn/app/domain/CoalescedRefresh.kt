package com.wireturn.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Batches notification requests from all services; reads current state when publishing. */
internal class CoalescedRefresh(
    private val scope: CoroutineScope,
    private val windowMs: Long = 750,
    private val refresh: () -> Unit
) {
    private var pending = false
    @Synchronized fun request() {
        if (pending) return
        pending = true
        scope.launch {
            delay(windowMs)
            synchronized(this@CoalescedRefresh) { pending = false }
            refresh()
        }
    }
}
