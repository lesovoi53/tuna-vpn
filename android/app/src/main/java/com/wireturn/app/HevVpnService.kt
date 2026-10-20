package com.wireturn.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.XraySettings.Companion.DEFAULT_SOCKS_BIND_ADDRESS
import com.wireturn.app.kernel.NativeTunnelRuntime
import com.wireturn.app.viewmodel.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

@Suppress("unused")
internal class HevSocks5Tunnel {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    companion object {
        init {
            System.loadLibrary("hevsocks5")
        }
    }
}

@Suppress("VpnServicePolicy")
class HevVpnService : VpnService() {
    private val hevTunnel = HevSocks5Tunnel()
    private val nativeRuntime = NativeTunnelRuntime(
        { path, fd -> hevTunnel.TProxyStartService(path, fd) },
        { hevTunnel.TProxyStopService(); Unit }
    )
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var activeTunMtu: com.wireturn.app.domain.TunMtuPolicy.Snapshot? = null
    private val isStopping = AtomicBoolean(false)
    private val nativeLock = Mutex()
    private var startJob: kotlinx.coroutines.Job? = null
    private fun trace(event: String) = AppLogsState.addLog(
        "[VPN lifecycle] service=${System.identityHashCode(this)} t=${android.os.SystemClock.elapsedRealtime()} $event"
    )

    private var physicalNetworkLease: java.io.Closeable? = null
    @Volatile private var vpnEstablished = false
    @Volatile private var underlyingNetwork: Network? = null
    @Volatile private var activeTunAddresses: com.wireturn.app.kernel.TunAddressPolicy.Addresses? = null

    private fun registerUnderlyingNetworkTracking() {
        physicalNetworkLease = com.wireturn.app.kernel.PhysicalNetwork.acquire(this) { snapshot ->
            underlyingNetwork = snapshot.network
            if (vpnEstablished) {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val properties = snapshot.network?.let(cm::getLinkProperties)
                val addresses = activeTunAddresses
                if (properties != null && addresses != null) {
                    val occupied = properties.routes.map { it.destination }.filter { it.prefixLength > 0 }
                    val conflict = listOf(addresses.ipv4, addresses.ipv6).any { address ->
                        val bytes = java.net.InetAddress.getByName(address).address
                        occupied.any { com.wireturn.app.kernel.TunAddressPolicy.Prefix(it.address.address, it.prefixLength).contains(bytes) }
                    }
                    if (conflict) {
                        VpnServiceState.updateStatus(VpnState.Error("Адрес VPN пересекается с новой локальной сетью; переподключение"))
                        stopVpn() // Preserve enabled preference; the supervisor selects fresh addresses.
                        return@acquire
                    }
                }
                try { setUnderlyingNetworks(snapshot.network?.let { arrayOf(it) }) }
                catch (failure: Exception) {
                    PersistentDiagnosticLog.append("[VPN dependency] update failed=${failure.javaClass.simpleName}")
                }
            }
        }
    }

    private fun unregisterUnderlyingNetworkTracking() {
        physicalNetworkLease?.close()
        physicalNetworkLease = null
        underlyingNetwork = null
    }

    private fun disableVpnMode() {
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPreferences(context)
                if (prefs.vpnSettingsFlow.first().enabled) {
                    prefs.setVpnEnabled(false)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
        registerUnderlyingNetworkTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        trace("onStartCommand id=$startId action=${action ?: "START"} state=${VpnServiceState.state.value.javaClass.simpleName}")
        if (action == ACTION_STOP) {
            // An explicit external stop should always clear a stale Error, unlike stopVpn()'s
            // own guard which preserves one set by a failed establish() in the same run.
            if (VpnServiceState.state.value is VpnState.Error) {
                VpnServiceState.updateStatus(VpnState.Idle)
            }
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_BY_USER) {
            VpnServiceState.updateStatus(VpnState.Idle)
            NotificationHelper.updateNotification(this)
            disableVpnMode()
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_UPDATE_TARGET) {
            val defaultSocks = DEFAULT_SOCKS_BIND_ADDRESS
            val socks5Addr = intent.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks
            val socks5User = intent.getStringExtra(EXTRA_SOCKS5_USER)
            val socks5Pass = intent.getStringExtra(EXTRA_SOCKS5_PASS)
            serviceScope.launch { updateTarget(socks5Addr, socks5User, socks5Pass) }
            return START_STICKY
        }

        val currentState = VpnServiceState.state.value
        val isStarting = startJob?.isActive == true
        if (tunInterface != null || currentState == VpnState.Running || (isStarting && currentState == VpnState.Starting)) {
            AppLogsState.addLog(getString(R.string.log_vpn_service_active, currentState.toString(), isStarting))
            return START_STICKY
        }
        isStopping.set(false)
        VpnServiceState.updateStatus(VpnState.Starting)

        try {
            val notification = NotificationHelper.buildNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLogsState.addLog(getString(R.string.log_vpn_foreground_failed, e.message ?: "Unknown"))
        }

        val defaultSocks = DEFAULT_SOCKS_BIND_ADDRESS
        val socks5Addr = intent?.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks
        val socks5User = intent?.getStringExtra(EXTRA_SOCKS5_USER)
        val socks5Pass = intent?.getStringExtra(EXTRA_SOCKS5_PASS)

        startJob = serviceScope.launch {
            startVpn(socks5Addr, socks5User, socks5Pass)
        }
        return START_STICKY
    }

    private fun buildConfigYaml(socks5Addr: String, socks5User: String?, socks5Pass: String?, tunMtu: com.wireturn.app.domain.TunMtuPolicy.Snapshot): String {
        val lastColon = socks5Addr.lastIndexOf(':')
        val socks5Host = if (lastColon > 0) socks5Addr.substring(0, lastColon) else socks5Addr
        val socks5Port = if (lastColon > 0) socks5Addr.substring(lastColon + 1).toIntOrNull() ?: 1080 else 1080

        val authConfig = if (!socks5User.isNullOrBlank() && !socks5Pass.isNullOrBlank()) {
            "\n  username: '$socks5User'\n  password: '$socks5Pass'"
        } else {
            ""
        }

        return """
tunnel:
  mtu: ${tunMtu.mtu}
socks5:
  port: $socks5Port
  address: '$socks5Host'
  udp: 'udp'$authConfig
mapdns:
  address: $MAPDNS_ADDRESS
  port: 53
  network: $MAPDNS_NETWORK
  netmask: $MAPDNS_NETMASK
  cache-size: 10000
misc:
  task-stack-size: 24576
  tcp-buffer-size: 4096
  max-session-count: 1200
  connect-timeout: 10000
  tcp-read-write-timeout: $TCP_IDLE_TIMEOUT_MS
  udp-read-write-timeout: 60000
  log-file: stderr
  log-level: warn
""".trimIndent()
    }

    // Repoints the relay at a new SOCKS5 target without touching the tun interface - a fresh
    // establish() would switch the device's default network and disrupt every other app.
    private suspend fun updateTarget(socks5Addr: String, socks5User: String? = null, socks5Pass: String? = null) {
        try {
        nativeLock.withLock {
            val fd = synchronized(this@HevVpnService) { tunInterface?.fd }
            if (fd == null) {
                AppLogsState.addLog(getString(R.string.log_vpn_retarget_no_tun))
                return
            }

            nativeRuntime.stop()

            AppLogsState.addLog(getString(R.string.log_vpn_retarget, socks5Addr))
            val configuration = com.wireturn.app.kernel.PrivateRuntimeConfigFile.create(this@HevVpnService,
                buildConfigYaml(socks5Addr, socks5User, socks5Pass, requireNotNull(activeTunMtu)))
            val success = nativeRuntime.start(configuration.path, fd, configuration)

            if (success) {
                VpnServiceState.updateStatus(VpnState.Running)
                NotificationHelper.updateNotification(this@HevVpnService)
                AppLogsState.addLog(getString(R.string.log_vpn_start))
            } else {
                // Retain the TUN as a blocking route and retry the relay on the same FD.
                AppLogsState.addLog(getString(R.string.log_vpn_error, "retarget failed"))
                VpnServiceState.updateStatus(VpnState.Error("Не удалось восстановить VPN. Трафик заблокирован."))
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            AppLogsState.addLog(getString(R.string.log_vpn_error, e.message ?: "retarget failed"))
            VpnServiceState.updateStatus(VpnState.Error("Не удалось восстановить VPN. Трафик заблокирован."))
        }
    }

    private suspend fun startVpn(socks5Addr: String, socks5User: String? = null, socks5Pass: String? = null) {
        try {
            AppLogsState.addLog(getString(R.string.log_vpn_establishing))
            val prefs = AppPreferences(applicationContext)
            val vpnSettings = prefs.vpnSettingsFlow.first()
            val tunMtu = com.wireturn.app.domain.TunMtuPolicy.Snapshot(prefs.tunMtuFlow.first())

            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val occupied = cm.allNetworks.filter {
                cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
            }.flatMap { network ->
                cm.getLinkProperties(network)?.let { properties ->
                    properties.linkAddresses.map { com.wireturn.app.kernel.TunAddressPolicy.Prefix(it.address.address, it.prefixLength) } +
                        properties.routes.map { com.wireturn.app.kernel.TunAddressPolicy.Prefix(it.destination.address.address, it.destination.prefixLength) }
                }.orEmpty()
            }
            val addresses = com.wireturn.app.kernel.TunAddressPolicy.choose(occupied)

            val builder = this.Builder()
                .setSession("TUNA VPN")
                .setMtu(tunMtu.mtu)
                .addAddress(addresses.ipv4, 32)
                // Without this, Android never captures IPv6 into the tun - it leaks straight
                // out over the underlying network instead, unprotected.
                .addAddress(addresses.ipv6, 128)
                .addDnsServer(MAPDNS_ADDRESS)

            underlyingNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(cm.isActiveNetworkMetered)
            }

            if (!vpnSettings.filteringEnabled) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                builder.addDisallowedApplication(packageName)
                AppLogsState.addLog(getString(R.string.log_vpn_filtering_disabled))
            } else if (vpnSettings.bypassMode) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                builder.addDisallowedApplication(packageName)
                vpnSettings.excludedApps.forEach { pkg ->
                    try { builder.addDisallowedApplication(pkg) }
                    catch (e: Exception) { AppLogsState.addLog(getString(R.string.log_vpn_exclude_failed, pkg, e.message ?: "Unknown")) }
                }
            } else {
                if (vpnSettings.excludedApps.isNotEmpty()) {
                    var installedAllowedApps = 0
                    vpnSettings.excludedApps.forEach { pkg ->
                        try { builder.addAllowedApplication(pkg); installedAllowedApps++ }
                        catch (e: Exception) { AppLogsState.addLog(getString(R.string.log_vpn_include_failed, pkg, e.message ?: "Unknown")) }
                    }
                    // No accepted UID means unrestricted routing in Android's builder.
                    // Keep the same no-default-route behavior as an empty allowlist.
                    if (installedAllowedApps > 0) {
                        builder.addRoute("0.0.0.0", 0)
                        builder.addRoute("::", 0)
                    } else {
                        AppLogsState.addLog(getString(R.string.log_vpn_include_empty))
                    }
                } else {
                    AppLogsState.addLog(getString(R.string.log_vpn_include_empty))
                }
            }

            // establish() can transiently return null right after another app releases VPN
            // ownership; the handover window isn't predictable, so retry with backoff.
            var established = builder.establish()
            for (retryDelayMs in longArrayOf(300L, 700L, 1500L, 3000L)) {
                if (established != null || isStopping.get()) break
                delay(retryDelayMs.milliseconds)
                established = builder.establish()
            }
            if (established == null) {
                AppLogsState.addLog(getString(R.string.log_vpn_tun_failed))
                VpnServiceState.updateStatus(VpnState.Error(getString(R.string.error_connecting)))
                NotificationHelper.updateNotification(this@HevVpnService)
                // No disableVpnMode() here - this is a failed attempt, not the user opting out;
                // leave VPN mode on. CoreService's VPN supervisor periodically retries establish()
                // itself while state stays Error (see startVpnSupervisor()'s pendingVpnRetryJob).
                stopSelf()
                return
            }
            // Take ownership before memory allocation or a suspension can fail. stopVpn/onDestroy
            // must always be able to close an established descriptor exactly once.
            synchronized(this@HevVpnService) {
                tunInterface = established
                activeTunMtu = tunMtu
                VpnServiceState.tunEstablished = true
            }
            vpnEstablished = true
            activeTunAddresses = addresses

            val tunFd = established.fd
            trace("establish success fd=$tunFd")

            nativeLock.withLock {
                if (isStopping.get()) {
                    // The serialized Stop owns descriptor cleanup.
                    if (VpnServiceState.state.value == VpnState.Starting) {
                        VpnServiceState.updateStatus(VpnState.Idle)
                    }
                    return
                }
                
                AppLogsState.addLog(getString(R.string.log_vpn_starting, tunFd, socks5Addr))
                val configuration = com.wireturn.app.kernel.PrivateRuntimeConfigFile.create(this@HevVpnService,
                    buildConfigYaml(socks5Addr, socks5User, socks5Pass, tunMtu))
                val success = nativeRuntime.start(configuration.path, tunFd, configuration)
                trace("JNI start returned=$success fd=$tunFd")
                
                if (!success) {
                    throw RuntimeException("Native hev-socks5-tunnel failed to start")
                }
                
                VpnServiceState.updateStatus(VpnState.Running)
                NotificationHelper.updateNotification(this@HevVpnService)
                AppLogsState.addLog(getString(R.string.log_vpn_start))
            }

        } catch (e: Exception) {
            trace("start failed type=${e.javaClass.simpleName} cancelled=${e is kotlinx.coroutines.CancellationException} stopping=${isStopping.get()} cause=${e.cause?.javaClass?.simpleName ?: "none"} message=${e.message}")
            AppLogsState.addLog(getString(R.string.log_vpn_error, e.message ?: "Unknown"))
            if (e !is kotlinx.coroutines.CancellationException) {
                VpnServiceState.updateStatus(VpnState.Error(e.message ?: "Unknown error"))
            } else if (VpnServiceState.state.value == VpnState.Starting) {
                VpnServiceState.updateStatus(VpnState.Idle)
            }
            if (e is kotlinx.coroutines.CancellationException || !VpnServiceState.tunEstablished || isStopping.get()) stopVpn()
            // A failed native start after establish keeps Android's blocking route.
        }
    }

    private fun stopVpn() {
        trace("stopVpn startJobActive=${startJob?.isActive} hevRunning=${nativeRuntime.isRunning} fd=${runCatching { tunInterface?.fd }.getOrNull()}")
        isStopping.set(true)
        startJob?.cancel()
        vpnEstablished = false

        serviceScope.launch {
            nativeLock.withLock {
                try {
                    // Read ownership only after any in-flight JNI start has completed.
                    if (nativeRuntime.stop()) {
                        AppLogsState.addLog(getString(R.string.log_vpn_stopped))
                    }
                } catch (e: Exception) {
                    AppLogsState.addLog(getString(R.string.log_vpn_stop_error, e.message ?: "Unknown"))
                }

                synchronized(this@HevVpnService) {
                    tunInterface?.let { try { it.close() } catch (_: Exception) {} }
                    tunInterface = null
                    activeTunMtu = null
                    VpnServiceState.tunEstablished = false
                }
            }

            withContext(Dispatchers.Main) {
                if (!isStopping.get()) return@withContext // New start already happened

                val currentState = VpnServiceState.state.value
                if (currentState !is VpnState.Error) {
                    VpnServiceState.updateStatus(VpnState.Idle)
                }
                NotificationHelper.updateNotification(this@HevVpnService)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        trace("onDestroy startJobActive=${startJob?.isActive} hevRunning=${nativeRuntime.isRunning}")
        super.onDestroy()
        // Do the Idle reset and final notification refresh inline (not via stopVpn(), whose
        // cleanup coroutine cancelling the scope right after would kill before it runs).
        isStopping.set(true)
        startJob?.cancel()
        vpnEstablished = false
        unregisterUnderlyingNetworkTracking()

        serviceScope.launch {
            nativeLock.withLock {
                try {
                    if (nativeRuntime.stop()) {
                        AppLogsState.addLog(getString(R.string.log_vpn_stopped))
                    }
                } catch (e: Exception) {
                    AppLogsState.addLog(getString(R.string.log_vpn_stop_error, e.message ?: "Unknown"))
                }
                synchronized(this@HevVpnService) {
                    tunInterface?.let { try { it.close() } catch (_: Exception) {} }
                    tunInterface = null
                    activeTunMtu = null
                    VpnServiceState.tunEstablished = false
                }
            }

            val currentState = VpnServiceState.state.value
            if (currentState !is VpnState.Error) {
                VpnServiceState.updateStatus(VpnState.Idle)
            }
            NotificationHelper.updateNotification(this@HevVpnService)
            serviceScope.cancel()
        }
    }

    override fun onRevoke() {
        trace("onRevoke")
        stopVpn()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "STOP"
        const val ACTION_STOP_BY_USER = "STOP_BY_USER"
        // Repoints the native relay at a new SOCKS5 target on an already-established tun -
        // see updateTarget(). Only valid while the tun is up; falls back silently otherwise.
        const val ACTION_UPDATE_TARGET = "UPDATE_TARGET"
        const val EXTRA_SOCKS5_ADDR = "socks5_addr"
        const val EXTRA_SOCKS5_USER = "socks5_user"
        const val EXTRA_SOCKS5_PASS = "socks5_pass"

        // Five-minute reaping reset healthy idle sockets in the controlled tablet test.
        // Keep a finite idle bound; connection setup and maximum session count stay bounded.
        private const val TCP_IDLE_TIMEOUT_MS = 30 * 60 * 1000
        // Not a real public resolver: Chrome auto-upgrades to DoH for known providers, which
        // would bypass the local fake-DNS responder. 198.18.0.1 (RFC 2544 benchmark range)
        // isn't on Chrome's built-in DoH provider list, so it can't trigger that.
        private const val MAPDNS_ADDRESS = "198.18.0.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
    }
}

