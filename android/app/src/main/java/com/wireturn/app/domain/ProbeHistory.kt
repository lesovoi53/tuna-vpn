package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import java.security.MessageDigest

enum class ProbeKind { @SerializedName("url") URL, @SerializedName("speed") SPEED }

/** Current shared tuning is safe metadata; immutable snapshots capture its value before probing. */
object ProbeHistoryEnvironment { @Volatile var fragmentation: String = "null" }

/** Only measurements and a one-way configuration digest are persisted, never links or keys. */
data class SavedProbeResult(
    @SerializedName("profileId") val profileId: String,
    @SerializedName("kind") val kind: ProbeKind,
    @SerializedName("configDigest") val configDigest: String,
    @SerializedName("measuredAt") val measuredAt: Long,
    @SerializedName("latencyMs") val latencyMs: Long? = null,
    @SerializedName("bytesPerSecond") val bytesPerSecond: Double? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("groupId") val groupId: String? = null,
    @SerializedName("endpointLabel") val endpointLabel: String? = null,
    @SerializedName("selectionLatencyMs") val selectionLatencyMs: Long? = null
) {
    fun isStale(profile: Profile): Boolean = profile.id != profileId || configDigest != probeConfigDigest(profile)
}

private val probeGson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()

fun probeConfigDigest(profile: Profile, fragmentation: String = ProbeHistoryEnvironment.fragmentation): String {
    // Exclude display name, subscription identity/baselines and favorites: these do not change a tunnel.
    val value = com.google.gson.JsonObject().apply {
        addProperty("fragmentationDefault", fragmentation)
        add("kernel", probeGson.toJsonTree(profile.kernelConfig, KernelConfig::class.java))
        addProperty("xrayEnabled", profile.xrayEnabled)
        addProperty("xrayProtocol", profile.xrayProtocol.name)
        add("wg", probeGson.toJsonTree(profile.wgConfig))
        add("vless", probeGson.toJsonTree(profile.vlessConfig))
    }
    return MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun List<SavedProbeResult>.latestProbe(profile: Profile, kind: ProbeKind, groupId: String? = null): SavedProbeResult? =
    filter { it.profileId == profile.id && it.kind == kind && (groupId == null || it.groupId == groupId) }
        .maxByOrNull { it.measuredAt }

/** Keep the last measurement of each kind/scope; cancellation does not overwrite a prior result. */
fun mergeProbeHistory(old: List<SavedProbeResult>, incoming: SavedProbeResult, limit: Int = 4000): List<SavedProbeResult> {
    require(limit > 0)
    val previous = old.firstOrNull { it.profileId == incoming.profileId && it.kind == incoming.kind && it.groupId == incoming.groupId }
    if (previous != null && previous.measuredAt > incoming.measuredAt) return old
    return (old.filterNot { it.profileId == incoming.profileId && it.kind == incoming.kind && it.groupId == incoming.groupId } + incoming)
        .sortedByDescending { it.measuredAt }.take(limit)
}
