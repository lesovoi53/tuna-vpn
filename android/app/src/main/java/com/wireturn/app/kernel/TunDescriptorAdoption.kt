package com.wireturn.app.kernel

/** Serialize ownership publication with descriptor cleanup. A synchronous establish()
 * can return after cancellation and teardown, so a retired caller must close its result. */
internal fun <T : AutoCloseable> adoptTunDescriptor(
    ownerLock: Any,
    descriptor: T,
    isCurrent: () -> Boolean,
    publish: (T) -> Unit
): Boolean = synchronized(ownerLock) {
    if (!isCurrent()) {
        descriptor.close()
        false
    } else {
        publish(descriptor)
        true
    }
}
