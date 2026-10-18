package com.wireturn.app.domain

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlin.math.roundToInt

/** Android TUN and HEV must use one value for the lifetime of the descriptor. */
object TunMtuPolicy {
    const val DEFAULT = 1280
    const val MIN = 1280
    // Android's TUN max_mtu and packaged HEV's u16 pbuf length. HEV's custom
    // allocator uses size_t, so 65535 rounds to 65536 + pbuf header safely.
    const val MAX = 65535
    private val key = intPreferencesKey("vpn_tun_mtu")
    val presets = listOf(MIN, 1380, 1400, 1420, 1500, 9000, 16384, 32768, 49152, MAX)

    fun valid(value: Int) = value in MIN..MAX
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf(::valid)
    fun read(preferences: Preferences): Int = preferences[key]?.takeIf(::valid) ?: DEFAULT
    fun write(preferences: MutablePreferences, value: Int) {
        require(valid(value)) { "VPN MTU must be between $MIN and $MAX" }
        preferences[key] = value
    }
    fun sliderValue(fraction: Float): Int =
        (MIN + fraction.coerceIn(0f, 1f) * (MAX - MIN)).roundToInt().coerceIn(MIN, MAX)
    fun sliderFraction(value: Int): Float = (value.coerceIn(MIN, MAX) - MIN).toFloat() / (MAX - MIN)

    data class Snapshot(val mtu: Int) {
        init { require(valid(mtu)) }
    }
}
