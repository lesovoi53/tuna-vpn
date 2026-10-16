package com.wireturn.app.data

/** A one-use grant. Profile imports and ordinary settings setters cannot create one. */
class RoutingDisableConsent private constructor() {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun consume(): Boolean = consumed.compareAndSet(false, true)

    class Confirmation {
        private var secondStepAt: Long? = null
        fun continueToSecondStep(nowMillis: Long) { secondStepAt = nowMillis }
        fun cancel() { secondStepAt = null }
        fun confirm(nowMillis: Long): RoutingDisableConsent? {
            val started = secondStepAt ?: return null
            if (nowMillis - started < 650L) return null
            cancel()
            return RoutingDisableConsent()
        }
    }
}
