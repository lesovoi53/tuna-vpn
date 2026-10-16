package com.wireturn.app.domain

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException

/** Samples one counter source; never computes rates across sessions or sources. */
class TrafficSampler(
    private val clock: () -> Long,
    private val hev: suspend () -> LongArray?,
    private val xray: suspend (String) -> String?,
) {
    data class Sample(val rx: Long, val tx: Long, val rxSpeed: Long = 0, val txSpeed: Long = 0)
    private var previous: Sample? = null
    private var previousKey: String? = null
    private var previousTime = 0L
    fun reset() { previous = null; previousKey = null; previousTime = 0 }

    suspend fun poll(vpnRunning: Boolean, xrayPresent: Boolean, extended: Boolean,
        socket: String?, session: String): Sample? {
        // TUN counters describe the actual Android VPN for every upstream protocol.
        // Routing IPC can be missing/restarting while this tunnel still forwards traffic.
        val useHev = vpnRunning
        val key = when {
            useHev -> "tun:$session"
            xrayPresent && !extended && socket != null -> "xray:$session:$socket"
            else -> { reset(); return null }
        }
        val counters = try {
            if (useHev) hev()?.takeIf { it.size >= 4 }?.let { Sample(it[3], it[1]) }
            else xray(socket!!)?.let {
                val obj = JsonParser.parseString(it).asJsonObject
                val rx = obj.get("rx_bytes")?.asString?.toLongOrNull()
                val tx = obj.get("tx_bytes")?.asString?.toLongOrNull()
                if (rx == null || tx == null) null else Sample(rx, tx)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (counters == null || counters.rx < 0 || counters.tx < 0) { reset(); return null }
        val now = clock()
        val old = previous?.takeIf { previousKey == key && now > previousTime && counters.rx >= it.rx && counters.tx >= it.tx }
        val result = if (old == null) counters else counters.copy(
            rxSpeed = ((counters.rx - old.rx).toDouble() * 1000 / (now - previousTime)).toLong(),
            txSpeed = ((counters.tx - old.tx).toDouble() * 1000 / (now - previousTime)).toLong(),
        )
        previous = counters; previousKey = key; previousTime = now
        return result
    }
}
