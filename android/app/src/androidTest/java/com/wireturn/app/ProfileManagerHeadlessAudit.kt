package com.wireturn.app

import android.content.Context
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

/** Real import completion with no screen ViewModel. Only loopback HTTP and TEST-NET data. */
internal object ProfileManagerHeadlessAudit {
    suspend fun run(context: Context): String {
        check(!CoreServiceState.isRunning.value)
        val prefs = AppPreferences(context)
        val before = prefs.profilesFlow.first()
        val originalId = prefs.currentProfileIdFlow.first()
        val original = before.single { it.id == originalId }
        val originalGroup = prefs.activeConnectionGroupFlow.first()
        val manager = withContext(Dispatchers.Main) { ProfileManager.get(context) }
        val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
        val revision = AtomicInteger(443)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val name = "QA rc30 headless activation"
        var subscriptionId: String? = null
        var localId: String? = null
        val requestCount = AtomicInteger()
        try {
            executor.submit {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        requestCount.incrementAndGet()
                        val p = Profile("qa-headless-source", name, KernelConfig.Vpn(VpnConfig(
                            "vless://11111111-2222-4333-8444-555555555555@192.0.2.4:${revision.get()}?security=tls&type=tcp", "xray")))
                        val body = gson.toJson(ProfileBundle(name = name, profiles = listOf(p))).toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/headless"
            check(manager.fetchSubscription(url) is ImportStatus.Success)
            val imported = withTimeout(5000) { prefs.profilesFlow.first { ps -> ps.any { it.name == name } } }.single { it.name == name }
            subscriptionId = imported.subscriptionId!!
            localId = imported.id
            prefs.saveFullProfile(imported.id, imported)
            withTimeout(5000) { manager.currentProfileId.first { it == imported.id } }
            revision.set(444)
            check(manager.fetchSubscription(url, subscriptionId) is ImportStatus.Success)
            withTimeout(5000) {
                prefs.clientConfigFlow.first { (it.kernelConfig as? KernelConfig.Vpn)?.config?.uri?.contains(":444?") == true }
            }
            check(prefs.currentProfileIdFlow.first() == imported.id)
            check(!CoreServiceState.isRunning.value) { "Updating a stopped connection must not start VPN" }
            check(requestCount.get() == 2)
        } finally {
            subscriptionId?.let { id ->
                manager.deleteSubscription(id)
                withTimeout(5000) { prefs.subscriptionsFlow.first { subs -> subs.none { it.id == id } } }
            }
            if (prefs.currentProfileIdFlow.first() == localId) {
                prefs.saveFullProfile(original.id, original)
                prefs.setActiveConnectionGroup(originalGroup)
            }
            server.close(); executor.shutdownNow()
        }
        check(before.associateBy { it.id } == prefs.profilesFlow.first().associateBy { it.id })
        check(prefs.currentProfileIdFlow.first() == originalId)
        return "headless import/refresh activated latest config; stopped VPN remained stopped; original profiles and selection preserved"
    }
}
