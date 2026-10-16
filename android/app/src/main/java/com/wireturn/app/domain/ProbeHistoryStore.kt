package com.wireturn.app.domain

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide store shared by Home/settings ViewModels and the service-owned group runner. */
class ProbeHistoryStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("probe_history_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): List<SavedProbeResult> = runCatching {
        val raw = prefs.getString("results", null) ?: return emptyList()
        if (raw.length > 4_000_000) return emptyList()
        gson.fromJson(raw, Array<SavedProbeResult>::class.java).orEmpty().filter {
            !it.profileId.isNullOrBlank() && !it.configDigest.isNullOrBlank() &&
                (it.kind as ProbeKind?) != null && it.measuredAt > 0 &&
                (it.latencyMs == null || it.latencyMs >= 0) &&
                (it.bytesPerSecond == null || it.bytesPerSecond.isFinite() && it.bytesPerSecond >= 0)
        }.sortedByDescending { it.measuredAt }.take(4000)
    }.getOrDefault(emptyList())

    @Synchronized fun record(result: SavedProbeResult) {
        val next = mergeProbeHistory(mutable.value, result)
        mutable.value = next
        prefs.edit().putString("results", gson.toJson(next)).apply()
    }

    companion object {
        @Volatile private var instance: ProbeHistoryStore? = null
        fun get(context: Context): ProbeHistoryStore = instance ?: synchronized(this) {
            instance ?: ProbeHistoryStore(context.applicationContext).also { instance = it }
        }
    }
}
