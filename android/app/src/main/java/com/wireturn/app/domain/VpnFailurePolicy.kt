package com.wireturn.app.domain

/** Losing an upstream must not release Android's VPN route onto the physical network. */
object VpnFailurePolicy {
    fun retainTun(enabled: Boolean, established: Boolean, stopping: Boolean): Boolean =
        enabled && established && !stopping
}
