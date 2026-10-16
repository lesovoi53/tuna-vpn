package com.wireturn.app

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnEnginePolicy
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest

/** Test APK only. No profile edits, no subscription edits, no routing changes. */
object ProfileMatrixAudit {
    suspend fun run(context: Context, args: Bundle, progress: (Bundle) -> Unit) {
        val prefs = AppPreferences(context)
        val profiles = prefs.profilesFlow.first()
        val gson = Gson()
        val rows = mutableListOf<Map<String, Any?>>()
        val groups = prefs.connectionGroupsFlow.first().map { group ->
            mapOf("id" to group.id, "name" to group.name, "members" to group.memberIds.size,
                "missing" to group.memberIds.count { id -> profiles.none { it.id == id } },
                "valid" to runCatching { group.validate(profiles) }.isSuccess)
        }
        fun save() { File(context.filesDir, "qa-matrix.json").writeText(gson.toJson(mapOf(
            "version" to BuildConfig.VERSION_NAME, "profiles" to profiles.size, "groups" to groups, "rows" to rows))) }
        val seen = mutableSetOf<String>()
        val start = args.getString("start")?.toIntOrNull() ?: 0
        val end = args.getString("end")?.toIntOrNull() ?: profiles.size
        for ((index, profile) in profiles.withIndex()) {
            val vpn = (profile.kernelConfig as? KernelConfig.Vpn)?.config?.let(VpnEnginePolicy::apply)
            val kernel = if (vpn != null) KernelConfig.Vpn(vpn.copy(uri = vpn.uri.substringBefore('#'))) else profile.kernelConfig
            val hash = MessageDigest.getInstance("SHA-256").digest(gson.toJson(kernel).toByteArray()).joinToString("") { "%02x".format(it) }
            val duplicate = !seen.add(hash)
            if (index !in start until end) continue
            val base = mapOf<String, Any?>("index" to index, "id" to profile.id, "name" to profile.name,
                "protocol" to (vpn?.protocol ?: profile.kernelVariant.name), "configurationHash" to hash)
            if (duplicate || (vpn?.protocol == "shadowsocks" && vpn.uri.contains("2022-"))) {
                rows += base + mapOf("status" to "skipped", "reason" to if (duplicate) "duplicate" else "ss2022_user_excluded")
                save(); continue
            }
            val engines = listOf(vpn?.selectedEngine ?: "native")
            for (engine in engines) {
                val candidate = if (vpn != null) profile.copy(kernelConfig = KernelConfig.Vpn(vpn.copy(engine = engine))) else profile
                val began = System.currentTimeMillis()
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                val watcher = Thread {
                    try {
                        Thread.sleep(40_000)
                        if (!finished.get()) File(context.filesDir, "qa-matrix-stacks.txt").writeText(
                            Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                                thread.name + " " + thread.state + "\n" + stack.joinToString("\n")
                            })
                    } catch (_: InterruptedException) { }
                }.apply { isDaemon = true; start() }
                progress(Bundle().apply { putString("case", "$index/$engine"); putString("phase", "start") })
                var targetUsed = ""
                val outcome = runCatching {
                    withTimeout(35_000L) {
                        IsolatedProfileTest(context).withTunnel(candidate) { cfg ->
                            var success = false
                            for (url in listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
                                try {
                                    GroupProbe.measure(ConnectionGroup("audit", "Audit", profile.category, GroupTestType.URL_TEST,
                                        listOf(profile.id), testUrl = url, samples = 1, timeoutSeconds = 6), profile.id, cfg)
                                    targetUsed = url; success = true; break
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { currentCoroutineContext().ensureActive() }
                            }
                            check(success) { "Probe failed" }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                finished.set(true)
                watcher.interrupt()
                val row = base + mapOf("engine" to engine, "status" to if (outcome.isSuccess) "pass" else "fail",
                    "errorType" to outcome.exceptionOrNull()?.javaClass?.simpleName, "elapsedMs" to System.currentTimeMillis() - began,
                    "target" to targetUsed, "enginePolicy" to "protocol-with-config-exceptions")
                rows += row; save()
                progress(Bundle().apply { putString("case", "$index/$engine"); putString("status", row["status"].toString()) })
            }
        }
        save()
    }
}
