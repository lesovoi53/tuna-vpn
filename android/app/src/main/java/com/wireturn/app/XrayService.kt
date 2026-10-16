package com.wireturn.app

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

class XrayService : Service() {
    private fun poolSafeBind(address: String, fallback: Int): String =
        if (address.substringAfterLast(':').toIntOrNull() in 1080..1088)
            address.substringBeforeLast(':') + ":$fallback" else address

    private val process = AtomicReference<Process?>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var xrayJob: kotlinx.coroutines.Job? = null
    private val userStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private var restartCount = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var caBundlePath: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
        caBundlePath = CaBundleUtil.ensure(this)
    }

    // Masks credentials/keys for the app's own log, which the user may end up sharing for
    // support - the actual cmdArgs passed to the process are untouched.
    private fun redactedCommandLog(cmdArgs: List<String>): String {
        val sensitiveFlags = setOf("-proxy-user", "-proxy-pass", "-local-socks5", "-link", "-wg-private-key")
        return CommandLogRedactor.redact(cmdArgs, sensitiveFlags)
    }

    // VPN mode (HevVpnService) is supervised centrally from CoreService.startVpnSupervisor() -
    // CoreService's lifecycle spans the whole "any core running" duration, whereas XrayService is
    // only alive while Xray itself is enabled, so it can't be the sole authority once VPN mode also
    // needs to work directly against an OLCRTC/WEBDAV core's own socks5 with no Xray involved.

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentState = XrayServiceState.state.value
        if ((currentState == XrayState.Starting || currentState == XrayState.Running) && xrayJob?.isActive == true) {
            return START_STICKY
        }

        userStopped.set(false)
        restartCount = 0

        xrayJob?.cancel()
        process.getAndSet(null)?.destroyForcibly()
        
        XrayServiceState.updateStatus(XrayState.Starting)

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
            AppLogsState.addLog(getString(R.string.log_xray_foreground_failed, e.message ?: "Unknown"))
        }

        xrayJob = serviceScope.launch {
            val prefs = AppPreferences(this@XrayService)
            val rawWg = prefs.wgConfigFlow.first()
            val rawXray = prefs.xrayConfigFlow.first()
            val rawVless = prefs.vlessConfigFlow.first()
            val rawClient = CoreServiceState.session.value?.clientConfig ?: prefs.clientConfigFlow.first()
            val commonSettings = prefs.xraySettingsFlow.first().fillDefaults()
            prefs.saveXraySettings(commonSettings)
            val rawXraySettings = prefs.effectiveXraySettingsFlow.first()

            val wgConfig = rawWg.fillDefaults()
            val vlessConfig = rawVless.fillDefaults()
            val clientConfig = rawClient.fillDefaults()
            val xraySettings = rawXraySettings.fillDefaults()

            prefs.saveWgConfig(wgConfig)
            prefs.saveXrayConfig(rawXray)
            prefs.saveVlessConfig(vlessConfig)
            prefs.saveClientConfig(clientConfig)

            val snapshot = XrayConfigsSnapshot(
                wg = wgConfig,
                xray = rawXray,
                vless = vlessConfig,
                client = clientConfig,
                settings = xraySettings,
                hevEnabled = prefs.vpnSettingsFlow.first().enabled
            )
            startXray(snapshot)
        }

        return START_STICKY
    }

    private data class XrayConfigsSnapshot(
        val wg: WgConfig,
        val xray: XrayConfig,
        val vless: VlessConfig,
        val client: ClientConfig,
        val settings: XraySettings,
        val hevEnabled: Boolean
    )

    private suspend fun startXray(snapshot: XrayConfigsSnapshot) {
        val executable = "${applicationInfo.nativeLibraryDir}/libxray.so"
        var ownedProcess: Process? = null
        val dnsRun = com.wireturn.app.domain.DnsRuntimeTelemetry.begin()

        try {
            val wgConfig = snapshot.wg
            val xrayConfig = snapshot.xray
            val vlessConfig = snapshot.vless
            val runningClientConfig = snapshot.client
            // Keep the outer proxy separate from the transport listener and publish its
            // effective address to the VPN service.
            val xraySettings = com.wireturn.app.kernel.LocalProxyPorts.resolve(runningClientConfig, snapshot.settings)

            if (xraySettings.networkRouter == com.wireturn.app.data.NetworkRouter.SING_BOX_EXTENDED) {
                startExtendedRouter(snapshot, xraySettings) { ownedProcess = it }
                return
            }

            val isXrayVless = xrayConfig.enabled && xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS

            com.wireturn.app.kernel.ChainCompatibility.validate(runningClientConfig, xrayConfig, vlessConfig, xraySettings)
            val isSocks5Native = runningClientConfig.kernelVariant.isSocks5Native

            val isConfigValid = if (isSocks5Native) {
                // For OLCRTC/WebDAV, VLESS/WG config is optional, unless DualRoute or Socks5Chain is enabled
                if (isXrayVless && (vlessConfig.isDualRoute || vlessConfig.isSocks5Chain)) {
                    vlessConfig.isValid()
                } else {
                    true
                }
            } else {
                if (isXrayVless) {
                    vlessConfig.isValid()
                } else {
                    wgConfig.isValid()
                }
            }

            if (!isConfigValid) {
                AppLogsState.addLog(getString(R.string.log_xray_invalid_config))
                stopSelf()
                return
            }

            // Фиксируем только тот конфиг, который реально запускаем
            XrayServiceState.setSession(XrayServiceState.RunningSession(
                wg = if (isXrayVless) null else wgConfig,
                xray = xrayConfig,
                vless = if (isXrayVless) vlessConfig else null,
                settings = xraySettings
            ))
            
            val prefs = AppPreferences(this@XrayService)
            val socketName = "sys.ipc.${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}"

            val cmdArgs = mutableListOf(
                executable,
                "-listen", xraySettings.socksBindAddress,
                "-stats-socket", socketName
            )

            if (xraySettings.httpBindAddress.isNotBlank()) {
                cmdArgs.add("-http")
                cmdArgs.add(xraySettings.httpBindAddress)
            }

            if (xraySettings.isProxyAuthEnabled && xraySettings.proxyUser.isNotBlank()) {
                cmdArgs.add("-proxy-user")
                cmdArgs.add(xraySettings.proxyUser)
                cmdArgs.add("-proxy-pass")
                cmdArgs.add(xraySettings.proxyPass)
            }

            // Profile-independent settings, applied regardless of kernel/protocol.
            if (xraySettings.dns.isNotBlank()) {
                cmdArgs.add("-dns")
                cmdArgs.add(xraySettings.dns)
            }
            val policyFile = if (xrayConfig.routingEnabled) {
            val network = com.wireturn.app.data.SharedNetworkSettings.fromLegacy(xraySettings)
            val compiled = com.wireturn.app.kernel.XrayNetworkCompiler.compile(network, snapshot.hevEnabled)
            val happSource = network.routing.subscriptionSource
            val hasSubscription = compiled.subscriptionSource.isNotBlank()
            val routingPolicy = compiled.routing
            val geoMatches = routingPolicy.activeRules().map { it.match }.filter { it.startsWith("geosite:") || it.startsWith("geoip:") }
            if (hasSubscription) com.wireturn.app.domain.SubscriptionRoutingAssets.validate(this@XrayService, happSource, routingPolicy)
            if (geoMatches.isNotEmpty() && !hasSubscription) {
                require(com.wireturn.app.domain.GeoAssetsManager.filesExist(this@XrayService)) { "Скачайте базы категорий в настройках маршрутизации" }
                require(routingPolicy.geoSource.isBlank() || routingPolicy.geoSource == xraySettings.geoVariant) { "Для этих правил нужна база ${routingPolicy.geoSource}" }
            }
            val policyFile = java.io.File(filesDir, "routing-policy.json").apply {
                writeText(compiled.policyJson)
            }
            if (compiled.fakeDnsOwner == com.wireturn.app.kernel.FakeDnsOwner.XRAY) {
                cmdArgs.add("-fakedns")
            }
            if (hasSubscription && geoMatches.isNotEmpty()) {
                cmdArgs.add("-assets-path")
                cmdArgs.add(com.wireturn.app.domain.SubscriptionRoutingAssets.directory(this@XrayService, happSource).absolutePath)
            } else if (!hasSubscription && com.wireturn.app.domain.GeoAssetsManager.filesExist(this@XrayService)) {
                cmdArgs.add("-assets-path")
                cmdArgs.add(com.wireturn.app.domain.GeoAssetsManager.assetsDir(this@XrayService).absolutePath)
            }
            policyFile
            } else null

            val assetsArgument = cmdArgs.indexOf("-assets-path")
            XrayServiceState.updateAssetsDirectory(if (assetsArgument >= 0) cmdArgs[assetsArgument + 1] else null)

            if (isSocks5Native) {
                cmdArgs.add("-local-socks5")
                // socksAddr may be bound to 0.0.0.0; Xray needs a literal destination.
                val connectableSocksAddr = runningClientConfig.socksAddr.replace("0.0.0.0:", "127.0.0.1:")
                // Use credentials from the active kernel session when authentication is enabled.
                val socksAddr = if (KernelRegistry.get(runningClientConfig.kernelVariant).socks5SupportsAuth &&
                    runningClientConfig.isSocksAuthEnabled && runningClientConfig.socksUser.isNotBlank()) {
                    "${runningClientConfig.socksUser}:${runningClientConfig.socksPass}@$connectableSocksAddr"
                } else {
                    connectableSocksAddr
                }
                cmdArgs.add(socksAddr)
            } else {
                // For other kernels, always use local proxy address
                cmdArgs.add("-local-address")
                cmdArgs.add(runningClientConfig.connectableAddress)
            }

            if (isXrayVless) {
                if (vlessConfig.vlessLink.isNotBlank()) {
                    prefs.addVlessLinkToHistory(vlessConfig.vlessLink)
                }
                
                val shouldAddLink = if (isSocks5Native) {
                    (vlessConfig.isDualRoute || vlessConfig.isSocks5Chain) && vlessConfig.vlessLink.isNotBlank()
                } else {
                    true
                }

                if (shouldAddLink) {
                    cmdArgs.addAll(listOf("-link", vlessConfig.vlessLink))
                }

                if (vlessConfig.mux != "0" && vlessConfig.mux.isNotBlank()) {
                    cmdArgs.add("-mux")
                    cmdArgs.add(vlessConfig.mux)
                }

                if (vlessConfig.isDualRoute && vlessConfig.directAddress.isNotBlank()) {
                    cmdArgs.add("-direct-address")
                    cmdArgs.add(vlessConfig.directAddress)
                    cmdArgs.add("-hc-interval")
                    cmdArgs.add(vlessConfig.hcInterval)
                    if (vlessConfig.hcDestination.isNotBlank()) {
                        cmdArgs.add("-hc-destination")
                        cmdArgs.add(vlessConfig.hcDestination)
                    }
                }

                // Only meaningful when -local-socks5 is actually the socks5-native kernel's
                // proxy (isSocks5Native) - vless-client requires -local-socks5 to be set for
                // -socks5-chain, and other kernels dial via -local-address instead.
                if (isSocks5Native && vlessConfig.isSocks5Chain) {
                    cmdArgs.add("-socks5-chain")
                }
            } else if (!isSocks5Native) {
                cmdArgs.addAll(listOf(
                    "-wg-private-key", wgConfig.privateKey,
                    "-wg-public-key", wgConfig.publicKey,
                    "-wg-endpoint", runningClientConfig.connectableAddress,
                    "-wg-address", wgConfig.address,
                    "-wg-mtu", wgConfig.mtu,
                    "-wg-keepalive", wgConfig.persistentKeepalive)
                )
            }

            AppLogsState.addLog(getString(R.string.log_xray_starting, redactedCommandLog(cmdArgs)))
            val proc = withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                builder.environment().remove("TUNA_ROUTING_FILE")
                builder.environment().remove("TUNA_BOOTSTRAP_DNS")
                builder.environment().remove(com.wireturn.app.kernel.SnellDestinationResolver.ENVIRONMENT_KEY)
                if (com.wireturn.app.kernel.SnellDestinationResolver.forTunnel(
                        snapshot.client.kernelConfig,
                        fakeDnsEnabled = "-fakedns" in cmdArgs, hevEnabled = snapshot.hevEnabled)) {
                    builder.environment()[com.wireturn.app.kernel.SnellDestinationResolver.ENVIRONMENT_KEY] = "1"
                }
                if (snapshot.settings.bootstrapDns.isNotBlank()) {
                    builder.environment()["TUNA_BOOTSTRAP_DNS"] = com.wireturn.app.data.BootstrapDns.parse(snapshot.settings.bootstrapDns)!!.xrayAddress()
                    builder.environment()["GODEBUG"] = "netdns=go"
                }
                policyFile?.let { builder.environment()["TUNA_ROUTING_FILE"] = it.absolutePath }
                if (snapshot.client.useCustomCerts) {
                    caBundlePath?.let { builder.environment()["SSL_CERT_FILE"] = it }
                }
                currentCoroutineContext().ensureActive()
                builder.start().also { ownedProcess = it }
            }
            ownedProcess = proc
            process.set(proc)
            XrayServiceState.updateStatsSocketName(socketName)
            XrayServiceState.updateStatus(XrayState.Starting)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var started = false
                var linesProcessed = 0
                while (true) {
                    val rawLine = reader.readLine() ?: break
                    val cleanLine = AppLogsState.stripAnsi(rawLine)
                    com.wireturn.app.domain.DnsRuntimeTelemetry.accept(dnsRun, cleanLine)
                    AppLogsState.addLog("* [Xray] $cleanLine")
                    linesProcessed++

                    if (!started && (cleanLine.contains("Xray started") || 
                        cleanLine.contains("proxy on") || 
                        cleanLine.contains("Listening"))) {
                        started = true
                        
                        if (!(isXrayVless && vlessConfig.isDualRoute)) {
                            XrayServiceState.updateStatus(XrayState.Running)
                        } else {
                            XrayServiceState.updateStatus(XrayState.Connecting)
                        }
                    }

                    if (isXrayVless && vlessConfig.isDualRoute) {
                        handleDualRouteLog(cleanLine, socketName)
                    }
                }
            }
            val exitCode = withContext(Dispatchers.IO) {
                proc.waitFor()
            }
            AppLogsState.addLog(getString(R.string.log_xray_exited, exitCode))
        } catch (_: InterruptedIOException) {
            // pass
        } catch (_: CancellationException) {
            // normal cancellation on restart
        } catch (e: Exception) {
            if (e is IllegalArgumentException) {
                CoreServiceState.setStatus(CoreStatus.Error(e.message ?: "Неподдерживаемая настройка маршрутизатора"))
            }
            AppLogsState.addLog(getString(R.string.log_xray_error, e.message ?: "Unknown"))
        } finally {
            com.wireturn.app.domain.DnsRuntimeTelemetry.end(dnsRun)
            val child = ownedProcess
            if (child != null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                child.destroy()
                if (!child.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    child.destroyForcibly()
                    child.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            if (process.compareAndSet(child, null)) XrayServiceState.updateStatsSocketName(null)
            
            // Only set Idle if we are NOT being cancelled by a new start command
            val isJobActive = try {
                currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true
            } catch (_: Exception) {
                false
            }
            if (isJobActive) {
                XrayServiceState.updateStatus(XrayState.Idle)
            }
            
            NotificationHelper.updateNotification(this@XrayService)
            if (userStopped.get()) {
                AppLogsState.addLog(getString(R.string.log_xray_stopped_by_user))
                NotificationHelper.updateNotification(this@XrayService)
                stopSelf()
            } else if (isJobActive && CoreServiceState.status.value !is CoreStatus.Error) {
                scheduleWatchdogRestart(snapshot)
            }
        }
    }

    private suspend fun startExtendedRouter(
        snapshot: XrayConfigsSnapshot,
        settings: XraySettings,
        onProcess: (Process) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(snapshot.xray.routingEnabled) { "Для общего extended включите маршрутизацию" }
        require(!snapshot.xray.enabled) { "Цепочка прокси пока требует общего Xray; отключите цепочку или выберите Xray" }
        require(snapshot.client.kernelVariant.isSocks5Native) { "Этот профиль требует общего Xray" }
        require(KernelRegistry.get(snapshot.client.kernelVariant).socks5SupportsAuth) { "Ядро профиля не поддерживает необходимую SOCKS-авторизацию" }
        require(settings.httpBindAddress.isBlank()) { "Общий extended пока поддерживает только локальный SOCKS-вход" }
        require(settings.isProxyAuthEnabled && snapshot.client.isSocksAuthEnabled) { "Общий extended требует авторизацию обоих локальных SOCKS-входов" }
        val network = com.wireturn.app.data.SharedNetworkSettings.fromLegacy(settings)
        val resolved = com.wireturn.app.domain.NetworkPolicyResolver.resolveForCompilation(network)
        val source = network.routing.subscriptionSource
        val directory = if (source.isNotBlank()) {
            com.wireturn.app.domain.SubscriptionRoutingAssets.validate(this@XrayService, source, resolved.routing)
            com.wireturn.app.domain.SubscriptionRoutingAssets.directory(this@XrayService, source)
        } else {
            require(resolved.routing.geoSource.isBlank() || resolved.routing.geoSource == settings.geoVariant) { "Для правил нужна другая geo-база" }
            com.wireturn.app.domain.GeoAssetsManager.assetsDir(this@XrayService)
        }
        val categories = com.wireturn.app.domain.GeoRuleSets.load(directory, resolved.routing.activeRules().map { it.match })
        val listener = com.wireturn.app.kernel.SingBoxNetworkCompiler.LocalSocks(settings.socksBindAddress, settings.proxyUser, settings.proxyPass)
        val upstream = com.wireturn.app.kernel.SingBoxNetworkCompiler.LocalSocks(
            snapshot.client.socksAddr.replace("0.0.0.0:", "127.0.0.1:"), snapshot.client.socksUser, snapshot.client.socksPass)
        val config = com.wireturn.app.kernel.SingBoxNetworkCompiler.compile(network, listener, upstream, categories, snapshot.hevEnabled)
        val work = java.io.File(filesDir, "network-router").apply { mkdirs() }
        val configFile = java.io.File(work, "extended.json")
        val temporary = java.io.File.createTempFile("extended-", ".json", work)
        try {
            temporary.writeText(config.toString())
            val native = "${applicationInfo.nativeLibraryDir}/libsingbox.so"
            val checkLog = java.io.File(work, "validation.log")
            val checker = ProcessBuilder(native, "check", "-c", temporary.absolutePath)
                .redirectErrorStream(true).redirectOutput(checkLog).start()
            try {
                require(checker.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { "Истёк таймаут проверки extended" }
                require(checker.exitValue() == 0) { "Ядро extended отклонило общую конфигурацию; выберите Xray и проверьте правила" }
            } finally {
                if (checker.isAlive) { checker.destroyForcibly(); checker.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
            java.nio.file.Files.move(temporary.toPath(), configFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            XrayServiceState.setSession(XrayServiceState.RunningSession(null, snapshot.xray, null, settings))
            XrayServiceState.updateStatsSocketName(null)
            currentCoroutineContext().ensureActive()
            val proc = ProcessBuilder(native, "run", "-c", configFile.absolutePath).redirectErrorStream(true).apply {
                environment().remove("TUNA_ROUTING_FILE")
                if (snapshot.client.useCustomCerts) caBundlePath?.let { environment()["SSL_CERT_FILE"] = it }
            }.start()
            onProcess(proc)
            process.set(proc)
            val ready = java.util.concurrent.atomic.AtomicBoolean(false)
            val startupTimeout = launch { delay(15_000); if (!ready.get()) proc.destroyForcibly() }
            try {
            com.wireturn.app.kernel.drainProcessOutput(proc, { proc.destroy() }) { line ->
                val clean = AppLogsState.stripAnsi(line)
                AppLogsState.addLog("* [Router extended] $clean")
                if (!ready.get() && clean.contains("sing-box started")) {
                    com.wireturn.app.kernel.RouterSocksProbe.verify(listener)
                    ready.set(true)
                    XrayServiceState.updateStatus(XrayState.Running)
                }
                clean.contains("FATAL", ignoreCase = true)
            }
            } finally { startupTimeout.cancel() }
            require(ready.get()) { "Общий extended не открыл авторизованный SOCKS-вход" }
        } finally { temporary.delete() }
    }

    private fun handleDualRouteLog(line: String, socketName: String) {
        when {
            line.contains("active route: direct") -> {
                XrayServiceState.updateStatus(XrayState.DirectRoute)
                if (CoreServiceState.isRunning.value && CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    AppLogsState.addLog(getString(R.string.log_dual_route_direct_established))
                    CoreServiceState.setStatus(CoreStatus.Suppressed)
                }
            }
            line.contains("active route: local") -> {
                XrayServiceState.updateStatus(XrayState.Running)
                
                val directUnreachable = line.contains("direct unreachable")
                val bothUnreachable = line.contains("both unreachable")
                
                if (directUnreachable || bothUnreachable) {
                    if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                        AppLogsState.addLog(getString(R.string.log_dual_route_direct_lost))
                        CoreServiceState.setStatus(CoreStatus.Connecting)
                    }
                    
                    if (!CoreServiceState.isRunning.value) {
                        AppLogsState.addLog(getString(R.string.log_dual_route_unreachable_start_tunnel))
                        serviceScope.launch {
                            val prefs = AppPreferences(applicationContext)
                            val cfg = prefs.clientConfigFlow.first()
                            CoreService.start(this@XrayService, cfg)
                            
                            // Force Xray to check connection after tunnel starts
                            CoreServiceState.isWorking.first { it }
                            delay(500.milliseconds)
                            try {
                                withContext(Dispatchers.IO) {
                                    LocalSocket().use { socket ->
                                        socket.soTimeout = 5_000
                                        socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                                        socket.outputStream.write("check 3\n".toByteArray())
                                        socket.inputStream.bufferedReader().readLine()
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogsState.addLog(getString(R.string.log_dual_route_check_failed, e.message ?: "Unknown"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scheduleWatchdogRestart(snapshot: XrayConfigsSnapshot) {
        restartCount++
        if (restartCount > MAX_RESTARTS) {
            AppLogsState.addLog(getString(R.string.log_xray_watchdog_limit, MAX_RESTARTS))
            stopSelf()
            return
        }
        
        XrayServiceState.updateStatus(XrayState.Starting)
        val delay = minOf(1000L * restartCount, 10000L)
        AppLogsState.addLog(getString(R.string.log_xray_watchdog_restart, delay.toInt(), restartCount, MAX_RESTARTS))
        
        handler.postDelayed({
            if (!userStopped.get()) {
                serviceScope.launch { startXray(snapshot) }
            }
        }, delay)
    }

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
        xrayJob?.cancel()
        process.getAndSet(null)?.destroyForcibly()

        // CoreService.startVpnSupervisor() reacts to this and either falls back to the
        // OLCRTC/WEBDAV core's own socks5 (if eligible) or stops VPN mode - not decided here.
        // Must run, and the notification refresh below with it, BEFORE the scope is cancelled:
        // this service's own observeStates collector (started in onCreate) lives on that scope,
        // so cancelling first meant nothing here ever reacted to the Idle transition, leaving a
        // stale "running" notification up whenever this was the last piece to shut down.
        XrayServiceState.updateStatus(XrayState.Idle)
        NotificationHelper.updateNotification(this)
        serviceScope.cancel()
    }

    companion object {
        private const val MAX_RESTARTS = 3
    }
}
