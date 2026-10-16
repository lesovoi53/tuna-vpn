package com.wireturn.app

import android.content.Context
import com.google.gson.Gson
import com.wireturn.app.data.*
import com.wireturn.app.domain.GroupProbe
import com.wireturn.app.domain.IsolatedProfileTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URI

/** Explicit device-only audit, absent from release. Never edits profile/account state. */
object SnellResolutionAudit {
    suspend fun run(context: Context): String = coroutineScope {
        val prefs = AppPreferences(context)
        val before = prefs.profilesFlow.first()
        val profiles = before.filter {
            val vpn = (it.kernelConfig as? KernelConfig.Vpn)?.config
            vpn?.protocol == "snell" && URI(vpn.uri).host in setOf("45.81.33.184", "89.19.223.185")
        }
        check(profiles.size == 2)
        val rows = mutableListOf<Map<String, Any>>()
        for (profile in profiles) {
            val result = withTimeout(45_000L) {
                IsolatedProfileTest(context).withTunnel(profile) { cfg ->
                    for (url in listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
                        GroupProbe.measure(ConnectionGroup("snell-audit", "Audit", profile.category,
                            GroupTestType.URL_TEST, listOf(profile.id), testUrl = url, samples = 1, timeoutSeconds = 10), profile.id, cfg)
                    }
                    "2/2"
                }
            }
            rows += mapOf("profile" to profile.name, "isolated" to result)
        }
        // Concurrent checks must own separate listeners and reap both cores.
        val concurrent = List(4) { index -> async(Dispatchers.IO) {
            val profile = profiles[index % profiles.size]
            withTimeout(45_000L) { IsolatedProfileTest(context).withTunnel(profile) { cfg ->
                GroupProbe.measure(ConnectionGroup("parallel", "Audit", profile.category,
                    GroupTestType.URL_TEST, listOf(profile.id), testUrl = "https://www.gstatic.com/generate_204",
                    samples = 1, timeoutSeconds = 10), profile.id, cfg)
            } }
        } }.awaitAll()
        rows += mapOf("parallelPassed" to concurrent.size)
        // Cancel after both processes were ready; teardown must finish promptly.
        for (profile in profiles) {
            val ready = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                IsolatedProfileTest(context).withTunnel(profile) { ready.complete(Unit); awaitCancellation() }
            }
            withTimeout(20_000L) { ready.await() }
            val started = System.nanoTime()
            withTimeout(8_000L) { job.cancelAndJoin() }
            rows += mapOf("cancelProfile" to profile.name, "cancelMs" to (System.nanoTime()-started)/1_000_000)
        }
        check(before == prefs.profilesFlow.first()) { "Profile data changed during read-only probes" }
        File(context.filesDir, "qa-snell-resolution.json").writeText(Gson().toJson(rows))
        "pass: isolated=4 parallel=4 cancel=2 profiles-unchanged"
    }
}
