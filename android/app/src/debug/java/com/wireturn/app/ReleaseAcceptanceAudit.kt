package com.wireturn.app

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnEnginePolicy
import com.wireturn.app.domain.*
import com.wireturn.app.kernel.NativeConfigCheck
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Debug-only audit. No endpoints/credentials in status output. Explicit QA actions only. */
object ReleaseAcceptanceAudit {
    suspend fun run(context: Context, args: Bundle): String {
        val prefs = AppPreferences(context)
        val rows = mutableListOf<Map<String, Any?>>()
        when (args.getString("releaseAction")) {
            "hev-cycle" -> {
                check(!VpnServiceState.tunEstablished) { "Stop VPN before native fixture" }
                val native = HevSocks5Tunnel()
                check(!native.TProxyIsRunning()) { "Native tunnel already running" }
                val config = File(context.filesDir, "hev-socks5-tunnel.yaml")
                check(config.exists())
                fun resources(label: String) {
                    val targets = File("/proc/self/fd").listFiles().orEmpty().map {
                        runCatching { android.system.Os.readlink(it.absolutePath) }.getOrDefault("")
                    }
                    rows += mapOf("label" to label, "pipes" to targets.count { it.startsWith("pipe:") },
                        "sockets" to targets.count { it.startsWith("socket:") })
                }
                resources("before")
                repeat(5) { index ->
                    // A private socket pair supplies pollable fake TUN I/O, with no packets
                    // and no VpnService/physical-network changes. Only JNI lifecycle runs.
                    val pair = android.os.ParcelFileDescriptor.createSocketPair()
                    try {
                        check(native.TProxyStartService(config.absolutePath, pair[0].fd))
                        delay(250)
                        check(native.TProxyIsRunning())
                    } finally {
                        native.TProxyStopService()
                        pair.forEach { it.close() }
                    }
                    resources("cycle-$index")
                }
            }
            "gc" -> {
                // Debug-only resource attribution: distinguish pending Java finalizers
                // from native descriptors that survive collection. Never used at runtime.
                System.gc()
                System.runFinalization()
                delay(1_000)
                System.gc()
                System.runFinalization()
            }
            "snapshot" -> Unit
            "import-file" -> {
                val text = File(context.filesDir, "qa-import-uri.txt").readText().trim()
                val decoded = requireNotNull(com.wireturn.app.kernel.KernelRegistry.decodeUri(text))
                val before = prefs.profilesFlow.first()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val manager = ProfileManager(prefs, scope)
                    withTimeout(5_000) { manager.profiles.first { it == before } }
                    val input = com.google.gson.JsonObject().apply {
                        addProperty("id", java.util.UUID.nameUUIDFromBytes(text.toByteArray()).toString())
                        addProperty("name", decoded.first.displayNameFromUri(text) ?: decoded.first.defaultProfileName)
                        addProperty("uri", text)
                    }
                    val result = manager.importProfiles(listOf(null to input.toString()))
                    require(result.second.isNotEmpty())
                    withTimeout(5_000) { prefs.profilesFlow.first { p -> result.second.all { added -> p.any { it.id == added.id } } } }
                    rows += mapOf("summary" to result.first)
                } finally { scope.cancel() }
            }
            "refresh" -> {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val manager = ProfileManager(prefs, scope)
                    val subscriptions = prefs.subscriptionsFlow.first()
                    for ((index, sub) in subscriptions.withIndex()) {
                        val result = withTimeout(90_000) { manager.fetchSubscription(sub.url, sub.id) }
                        rows += mapOf("subscriptionIndex" to index, "status" to result.javaClass.simpleName,
                            "summary" to (result as? ImportStatus.Success)?.summary,
                            "diagnostics" to AppLogsState.logs.value.takeLast(4).map { it.message })
                    }
                } finally { scope.cancel() }
            }
            "select" -> {
                val profiles = prefs.profilesFlow.first()
                val index = requireNotNull(args.getString("index")?.toIntOrNull())
                val profile = profiles[index]
                prefs.setActiveConnectionGroup(null)
                prefs.saveFullProfile(profile.id, profile)
            }
            "native" -> {
                for ((index, profile) in prefs.profilesFlow.first().withIndex()) {
                    if (index < (args.getString("start")?.toIntOrNull() ?: 0) ||
                        index >= (args.getString("end")?.toIntOrNull() ?: Int.MAX_VALUE)) continue
                    val vpn = (profile.kernelConfig as? KernelConfig.Vpn)?.config ?: continue
                    val began = System.currentTimeMillis()
                    val outcome = runCatching { NativeConfigCheck.check(context, VpnEnginePolicy.apply(vpn)) }
                    rows += mapOf("index" to index, "valid" to outcome.isSuccess,
                        "errorType" to outcome.exceptionOrNull()?.javaClass?.simpleName,
                        "elapsedMs" to System.currentTimeMillis() - began)
                    File(context.filesDir, "qa-native-progress.json").writeText(Gson().toJson(rows))
                    // Keep native error details private on-device; they may contain credentials.
                    outcome.exceptionOrNull()?.let { File(context.filesDir, "qa-native-error-$index.txt").writeText(it.toString()) }
                }
            }
            else -> error("Unknown release action")
        }
        val profiles = prefs.profilesFlow.first()
        val report = mapOf("version" to BuildConfig.VERSION_NAME, "action" to args.getString("releaseAction"), "rows" to rows,
            "currentProfileId" to prefs.currentProfileIdFlow.first(),
            "subscriptions" to prefs.subscriptionsFlow.first().map { mapOf("id" to it.id, "name" to it.name) },
            "profiles" to profiles.mapIndexed { i, p ->
                val vpn = (p.kernelConfig as? KernelConfig.Vpn)?.config
                mapOf("index" to i, "id" to p.id, "name" to p.name, "subscriptionId" to p.subscriptionId,
                    "protocol" to (vpn?.protocol ?: p.kernelVariant.name),
                    "engine" to vpn?.let(VpnEnginePolicy::assignedEngine), "valid" to !p.isEmpty())
            },
            "groups" to prefs.connectionGroupsFlow.first().map { g -> mapOf("id" to g.id, "name" to g.name,
                "members" to g.memberIds.size, "missing" to g.memberIds.count { id -> profiles.none { it.id == id } },
                "repairRequired" to g.repairRequired, "valid" to runCatching { g.validate(profiles) }.isSuccess) })
        File(context.filesDir, "qa-release.json").writeText(Gson().toJson(report))
        return "${args.getString("releaseAction")}_complete"
    }
}
