package com.wireturn.app.kernel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** One selection shared by HEV and all resolver clients, including pre-TUN startup. */
object PhysicalNetwork {
    data class Snapshot(val network: Network?, val generation: Long)
    private var state = Snapshot(null, 0)
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var dnsFingerprint: String? = null
    private val listeners = linkedMapOf<Any, (Snapshot) -> Unit>()

    @Synchronized fun snapshot(): Snapshot = state

    @Synchronized fun acquire(context: Context, listener: (Snapshot) -> Unit = {}): Closeable {
        val key = Any()
        listeners[key] = listener
        if (callback == null) {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            manager = cm
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update(this, network)
                override fun onLost(network: Network) {
                    synchronized(this@PhysicalNetwork) {
                        if (state.network == network) update(this, null)
                    }
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) onLost(network)
                }
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    synchronized(this@PhysicalNetwork) {
                        if (this !== callback || network != state.network) return
                        val fingerprint = properties.dnsServers.joinToString(",") { it.hostAddress.orEmpty() } +
                            (if (Build.VERSION.SDK_INT >= 28) "/${properties.isPrivateDnsActive}/${properties.privateDnsServerName}" else "") +
                            "/${properties.linkAddresses}/${properties.routes}"
                        if (dnsFingerprint != fingerprint) {
                            dnsFingerprint = fingerprint
                            state = Snapshot(network, state.generation + 1)
                            listeners.values.toList().forEach { it(state) }
                        }
                    }
                }
            }
            callback = cb
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            try {
                if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(request, cb, Handler(Looper.getMainLooper()))
                else cm.requestNetwork(request, cb, Handler(Looper.getMainLooper()))
                cm.activeNetwork?.takeIf { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true }
                    ?.let { update(cb, it) }
            } catch (error: Exception) {
                callback = null
                manager = null
                listeners.remove(key)
                throw error
            }
        }
        listener(state)
        return Closeable { release(key) }
    }

    @Synchronized private fun update(owner: ConnectivityManager.NetworkCallback, network: Network?) {
        if (owner !== callback || state.network == network) return
        dnsFingerprint = null
        state = Snapshot(network, state.generation + 1)
        listeners.values.toList().forEach { it(state) }
    }

    @Synchronized private fun release(key: Any) {
        listeners.remove(key)
        if (listeners.isNotEmpty()) return
        val old = callback
        callback = null
        old?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        manager = null
        state = Snapshot(null, state.generation + 1)
    }
}
