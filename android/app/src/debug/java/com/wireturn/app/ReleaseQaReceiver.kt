package com.wireturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File

/** ADB-only debug harness compiled together with the app, so R8 sees its dependencies. */
class ReleaseQaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val args = intent.extras ?: return
        val requestId = args.getString("requestId") ?: return
        if (args.getString("releaseAction") == "tun-cycle") {
            context.startForegroundService(Intent(context, ReleaseQaVpnService::class.java))
            return
        }
        if (args.getString("releaseAction") !in setOf("snapshot", "select", "gc", "hev-cycle")) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = withTimeout(8_000) { ReleaseAcceptanceAudit.run(app, args) }
                File(app.filesDir, "qa-request.json").writeText(Gson().toJson(mapOf("requestId" to requestId, "result" to result)))
            } catch (failure: Exception) {
                File(app.filesDir, "qa-request.json").writeText(Gson().toJson(mapOf("requestId" to requestId, "error" to failure.javaClass.simpleName)))
            } finally { pending.finish() }
        }
    }
}
