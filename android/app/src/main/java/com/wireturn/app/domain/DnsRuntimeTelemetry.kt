package com.wireturn.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-generation scoped observations; never infer a winner from the settings order. */
object DnsRuntimeTelemetry {
    data class Observation(val group: String, val index: Int, val reason: String)
    private val format = Regex("TUNA_DNS_STATUS (remote|domestic) ([1-8]) (selected|fallback|probe|negative)")
    private var generation = 0L
    private val mutable = MutableStateFlow<Map<String, Observation>>(emptyMap())
    val observations = mutable.asStateFlow()

    fun parse(line: String): Observation? {
        val fields = format.matchEntire(line)?.groupValues ?: return null
        return Observation(fields[1], fields[2].toInt(), fields[3])
    }

    @Synchronized fun begin(): Long {
        generation++
        mutable.value = emptyMap()
        return generation
    }

    @Synchronized fun accept(run: Long, line: String) {
        if (run != generation) return
        val observation = parse(line) ?: return
        mutable.value = mutable.value + (observation.group to observation)
    }

    @Synchronized fun end(run: Long) {
        if (run == generation) begin()
    }
}
