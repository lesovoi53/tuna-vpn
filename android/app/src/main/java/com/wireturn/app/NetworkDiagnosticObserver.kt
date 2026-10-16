package com.wireturn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Passive observation only: never requests/binds a network, sends traffic or acquires wake locks. */
object NetworkDiagnosticObserver {
    private val started = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun initialize(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            PersistentDiagnosticLog.enabled.collectLatest { enabled ->
                if (!enabled) return@collectLatest
                val cm = app.getSystemService(ConnectivityManager::class.java)
                val seen = ConcurrentHashMap<String, String>()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = record("available net=$network")
                    override fun onLost(network: Network) {
                        seen.remove("caps:$network"); seen.remove("link:$network")
                        record("lost net=$network")
                    }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val summary = describe(caps)
                        if (seen.put("caps:$network", summary) != summary) record("capabilities net=$network $summary")
                    }
                    override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                        // No DNS addresses, SSIDs, IPs, routes, domains or browsing history.
                        val mtu = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) props.mtu.toString() else "unavailable"
                        val summary = "dnsCount=${props.dnsServers.size} mtu=$mtu"
                        if (seen.put("link:$network", summary) != summary) record("link net=$network $summary")
                    }
                }
                var registered = false
                try {
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
                    cm.registerNetworkCallback(request, callback)
                    registered = true
                    while (isActive) {
                        val nets = cm.allNetworks.joinToString("; ") { n -> "$n:${describe(cm.getNetworkCapabilities(n))}" }
                        val core = when (CoreServiceState.status.value) {
                            CoreStatus.Idle -> "idle"
                            CoreStatus.Starting -> "starting"
                            CoreStatus.Connecting -> "connecting"
                            CoreStatus.Connected -> "connected"
                            CoreStatus.Suppressed -> "suppressed"
                            CoreStatus.Stopping -> "stopping"
                            CoreStatus.WaitingForNetwork -> "waiting_network"
                            is CoreStatus.CaptchaRequired -> "captcha"
                            is CoreStatus.Error -> "error"
                        }
                        record("heartbeat core=$core tun=${VpnServiceState.tunEstablished} generation=${VpnServiceState.generation} activeForApp=${cm.activeNetwork} networks=[$nets]")
                        delay(60_000)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    record("observer_error=${failure.javaClass.simpleName}")
                } finally {
                    if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }
        }
    }

    private fun record(value: String) = PersistentDiagnosticLog.append("[NetworkDiagnostic] $value")
    private fun describe(c: NetworkCapabilities?): String {
        if (c == null) return "unavailable"
        return "wifi=${c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} cellular=${c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} vpn=${c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} validated=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} metered=${!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}"
    }
}
