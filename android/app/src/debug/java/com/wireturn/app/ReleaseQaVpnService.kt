package com.wireturn.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import java.io.File
import com.google.gson.Gson
import kotlinx.coroutines.*

/** Debug-only Android establish/close fixture. No default route, DNS, JNI or user traffic. */
class ReleaseQaVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NotificationHelper.NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        scope.launch {
            val rows = mutableListOf<Map<String, Any>>()
            var failure: String? = null
            fun record(label: String) {
                val targets = File("/proc/self/fd").listFiles().orEmpty().map {
                    runCatching { android.system.Os.readlink(it.absolutePath) }.getOrDefault("")
                }
                rows += mapOf("label" to label, "pipes" to targets.count { it.startsWith("pipe:") },
                    "sockets" to targets.count { it.startsWith("socket:") })
            }
            try {
                check(!VpnServiceState.tunEstablished)
                record("before")
                repeat(5) { i ->
                    checkNotNull(Builder().setSession("TUNA local QA").addAddress("10.99.0.1", 32).establish())
                        .use { delay(300) }
                    delay(300)
                    record("cycle-$i")
                }
            } catch (e: Exception) { failure = e.javaClass.simpleName }
            finally {
                File(filesDir, "qa-tun-resources.json").writeText(Gson().toJson(mapOf("rows" to rows, "error" to failure)))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
