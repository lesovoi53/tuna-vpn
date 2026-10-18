package com.wireturn.app.vk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import kotlinx.coroutines.*

/** Activity host needed by qWDTT's original hidden/interactive VK WebViews. */
class VkHostApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: Job? = null
    override fun onCreate() {
        super.onCreate()
        // The SSH browser has its own cookie store and must not initialize VK WebViews or background updates.
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":serverauth")) {
            android.webkit.WebView.setDataDirectorySuffix("server-auth")
            return
        }
        val currentProcess = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            java.io.File("/proc/self/cmdline").inputStream().use { input ->
                val bytes = ByteArray(256)
                val count = input.read(bytes)
                if (count > 0) String(bytes, 0, count, Charsets.UTF_8).substringBefore('\u0000') else ""
            }
        }
        if (currentProcess == packageName) com.wireturn.app.kernel.PrivateRuntimeDirectory.removeAbandoned(this)
        com.wireturn.app.AppLogsState.configure(this); registerActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) {
        if (updateJob?.isActive != true) updateJob = updateScope.launch {
            com.wireturn.app.domain.RoutingUpdates.schedule(applicationContext)
            com.wireturn.app.domain.RoutingUpdates.run(applicationContext)
        }
        resumed = WeakReference(activity)
        if (activity !is com.wdtt.client.VkAuthActivity) com.wdtt.client.VkAuthWebViewManager.checkAndShowPendingAuth(activity)
    }
    override fun onActivityPaused(activity: Activity) { if (resumed.get() === activity) resumed.clear() }
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
    companion object { var resumed = WeakReference<Activity>(null); private set }
}
