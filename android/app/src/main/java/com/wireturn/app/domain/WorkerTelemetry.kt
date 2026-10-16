package com.wireturn.app.domain

/** Counts transport workers, not hashes, goroutines or application connections. */
object WorkerTelemetry {
    data class Sample(val active: Int?, val target: Int, val receivedAt: Long = 0) {
        fun fresh(now: Long) = active != null && now - receivedAt in 0..10_000
    }

    private val qwdtt = Regex("\\[СТАТИСТИКА] Активных:\\s*(\\d+)\\s*\\|")

    fun parse(line: String, protocol: String): Int? {
        val match = when (protocol) {
            "qwdtt" -> qwdtt.find(line)
            else -> null
        }
        return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..1024 }
    }
}
