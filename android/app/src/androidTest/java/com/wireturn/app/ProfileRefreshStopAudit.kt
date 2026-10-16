package com.wireturn.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.google.gson.GsonBuilder
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Real import callback during Stopping; service dispatch is intercepted, so no VPN is started. */
internal object ProfileRefreshStopAudit {
    suspend fun run(context: Context): String {
        check(CoreServiceState.status.value is CoreStatus.Idle)
        val starts = AtomicInteger()
        val intercepted = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? {
                starts.incrementAndGet()
                return ComponentName(packageName, CoreService::class.java.name)
            }
        }
        val prefs = AppPreferences(intercepted)
        val before = prefs.profilesFlow.first()
        val originalId = prefs.currentProfileIdFlow.first()
        val original = before.single { it.id == originalId }
        val originalGroup = prefs.activeConnectionGroupFlow.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val manager = ProfileManager(prefs, scope)
        val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
        val revision = AtomicInteger(443)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val name = "QA rc30 refresh while stopping"
        var subscriptionId: String? = null
        try {
            executor.submit {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        val profile = Profile("qa-stop-source", name, KernelConfig.Vpn(VpnConfig(
                            "vless://11111111-2222-4333-8444-555555555555@192.0.2.4:${revision.get()}?security=tls", "xray")))
                        val body = gson.toJson(ProfileBundle(name = name, profiles = listOf(profile))).toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body);flush()
                        }
                    }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/stop-race"
            check(manager.fetchSubscription(url) is ImportStatus.Success)
            val profile = prefs.profilesFlow.first().single { it.name == name }
            subscriptionId = profile.subscriptionId!!
            prefs.saveFullProfile(profile.id, profile)
            withTimeout(5000) { manager.currentProfileId.first { it == profile.id } }
            CoreServiceState.setStatus(CoreStatus.Stopping)
            withTimeout(5000) { CoreServiceState.isRunning.first { it } }
            revision.set(8443)
            check(manager.fetchSubscription(url, subscriptionId) is ImportStatus.Success)
            withTimeout(5000) { prefs.clientConfigFlow.first { (it.kernelConfig as? KernelConfig.Vpn)?.config?.uri?.contains(":8443?") == true } }
            delay(500)
            check(starts.get() == 0) { "Subscription dispatched ${starts.get()} start(s) while user was stopping" }
            check(CoreServiceState.status.value is CoreStatus.Stopping)
        } finally {
            CoreServiceState.setStatus(CoreStatus.Idle)
            prefs.saveFullProfile(original.id, original)
            prefs.setActiveConnectionGroup(originalGroup)
            subscriptionId?.let { id ->
                manager.deleteSubscription(id)
                withTimeout(5000) { prefs.subscriptionsFlow.first { subs -> subs.none { it.id == id } } }
            }
            scope.cancel();server.close();executor.shutdownNow()
        }
        check(before.associateBy { it.id } == prefs.profilesFlow.first().associateBy { it.id })
        check(prefs.currentProfileIdFlow.first() == originalId)
        return "refresh committed during Stopping without dispatching a start; original profiles and selection preserved"
    }
}
