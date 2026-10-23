package com.wireturn.app.kernel

/** The last endpoint requested from HEV, retained through gaps in upstream availability.
 * Merely observing an endpoint while HEV is Starting does not apply it. */
internal class VpnTargetState<T> {
    private var requested: T? = null

    fun needsUpdate(target: T): Boolean = requested != target

    fun markRequested(target: T) {
        requested = target
    }
}
