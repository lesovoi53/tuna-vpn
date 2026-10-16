package com.wireturn.app.domain

/** Tracks the one best network returned by an INTERNET + NOT_VPN request.
 * Callback state is synchronized because the recovery loop runs on an IO dispatcher.
 */
internal class PhysicalNetworkState {
    enum class Change { NONE, FIRST, CHANGED }
    private var selected: Long? = null
    private var online = false
    private var initialized = false

    @Synchronized fun available(handle: Long, eligible: Boolean? = null): Change {
        // onAvailable already matched the request. No synchronous capabilities
        // snapshot is required; Android delivers ordered capabilities next.
        if (eligible == false) return Change.NONE
        if (selected == handle && online) return Change.NONE
        selected = handle
        online = true
        return if (initialized) Change.CHANGED else {
            initialized = true
            Change.FIRST
        }
    }

    @Synchronized fun capabilities(handle: Long, eligible: Boolean): Boolean {
        if (selected != handle) return false
        online = eligible
        return true
    }
    @Synchronized fun lost(handle: Long) {
        if (selected == handle) { selected = null; online = false }
    }
    @Synchronized fun hasNetwork() = online
    @Synchronized fun clear() { selected = null; online = false; initialized = false }
}
