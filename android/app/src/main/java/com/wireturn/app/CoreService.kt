package com.wireturn.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.kernel.BinaryOutputState
import com.wireturn.app.kernel.KernelCommandContext
import com.wireturn.app.kernel.KernelLogContext
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.kernel.NetworkQuality
import com.wireturn.app.kernel.canUpdateConnectingStatus
import com.wireturn.app.kernel.markConnecting
import com.wireturn.app.kernel.drainProcessOutput
import com.wireturn.app.kernel.CoreCommandLifecycle
import com.wireturn.app.kernel.terminateCoreProcess
import com.wireturn.app.viewmodel.AppLifecycleState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds


class CoreService : Service() {
    private val process = AtomicReference<Process?>()

    // Non-null (i.e. != -1) only between a qWDTT "CAPTCHA_SOLVE|manual|..." request and its
    // resolution - see observeQwdttCaptchaResult(). Read/written from multiple serviceScope
    // coroutines (Dispatchers.IO is a thread pool, not single-threaded), hence Atomic rather than
    // a plain var - same reasoning as `process` above.
    private val pendingQwdttCaptchaSessionId = AtomicLong(-1L)
    private val userStopped = AtomicBoolean(false)
    private val plannedRestart = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    private val currentRunningCfg = AtomicReference<ClientConfig?>(null)
    // Serializes stopBinaryProcessGracefully() across all its callers - rapid profile switches can
    // otherwise start a new binary before the old one's process is confirmed dead.
    private val stopMutex = Mutex()
    private val physicalNetworkState = com.wireturn.app.domain.PhysicalNetworkState()
    
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var caBundlePath: String? = null
    private var restartCount = 0
    // Specific reason for the current failure, if a handler knows one - shown instead of the
    // generic core_failed message if the watchdog exhausts MAX_RESTARTS.
    private var lastKnownFailureReason: String? = null

    private lateinit var serviceScope: CoroutineScope
    private lateinit var coreCommands: CoreCommandLifecycle
    private var xraySupervisorJob: Job? = null
    private var vpnSupervisorJob: Job? = null
    private var networkDebounceJob: Job? = null

    // Adapters handing kernel/*/*.kt implementations only what they need from this Service,
    // instead of each one needing direct access to CoreService itself.
    private val commandContext = object : KernelCommandContext {
        override val filesDir get() = this@CoreService.filesDir
        override val nativeLibraryDir get() = applicationInfo.nativeLibraryDir
        override val xrayAssetsDir get() = com.wireturn.app.domain.GeoAssetsManager.assetsDir(this@CoreService)

    }
    private val logContext = object : KernelLogContext {
        override fun getString(resId: Int, vararg args: Any) = this@CoreService.getString(resId, *args)
        override fun updateNotification(text: String) = this@CoreService.updateNotification(text)
        override fun isNetworkAvailable() = this@CoreService.isNetworkAvailable()
        override suspend fun getNetworkQuality() = this@CoreService.getNetworkQuality()
        override suspend fun isNetworkMissingAndHandled() = this@CoreService.isNetworkMissingAndHandled()
        override fun launchCaptchaActivityIfForeground(url: String) = this@CoreService.launchCaptchaActivityIfForeground(url)
        override fun setPendingCaptchaSessionId(id: Long) { pendingQwdttCaptchaSessionId.set(id) }
        override fun setLastFailureReason(reason: String) { lastKnownFailureReason = reason }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        coreCommands = CoreCommandLifecycle(serviceScope)
        observeConnectionPower()
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
        observeCaptchaForNotification()
        observeQwdttCaptchaResult()
        observeErrorForNotification()
        startXraySupervisor()
        startVpnSupervisor()
        caBundlePath = CaBundleUtil.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP || action == ACTION_STOP_BY_USER) {
            com.wireturn.app.domain.ConnectionGroupRunner.get(applicationContext).release()
            handleStopAction(disableAutoLaunch = action == ACTION_STOP_BY_USER)
            return START_NOT_STICKY
        }

        moveToForeground()

        // При каждом явном вызове Start — перезапускаем цикл, чтобы подхватить возможные изменения в конфиге
        coreCommands.cancelBeforeStart {
            userStopped.set(false)
            plannedRestart.set(false)
            CoreServiceState.setStatus(CoreStatus.Starting)
        }
        coreCommands.start({ stopBinaryProcessGracefully() }) {
            // Stop cancels these observers. Reattach only after its entire cleanup has
            // finished, otherwise an old observer can stop resources of the new run.
            withContext(Dispatchers.Main) {
                if (xraySupervisorJob?.isActive != true) startXraySupervisor()
                if (vpnSupervisorJob?.isActive != true) startVpnSupervisor()
                CoreServiceState.setStatus(CoreStatus.Starting)
            }
            val prefs = AppPreferences(applicationContext)
            val cfg = prefs.clientConfigFlow.first()
            val profileName = prefs.currentProfileNameFlow.first().orEmpty()
            val vlessConfig = prefs.vlessConfigFlow.first()
            val xrayConfig = prefs.xrayConfigFlow.first()

            // Сразу фиксируем работающий конфиг для UI (с заполненными дефолтами)
            val filledCfg = cfg.fillDefaults()
            
            if (!filledCfg.isValid) {
                val errorRes = filledCfg.getValidationErrorResId() ?: R.string.error_settings_empty
                CoreServiceState.setStatus(CoreStatus.Error(getString(errorRes)))
                delay(3_000.milliseconds)
                finishFailedRun()
                return@start
            }

            prefs.saveClientConfig(filledCfg)
            val runtimeCfg = com.wireturn.app.data.kernel.VpnFragmentation.applyDefault(filledCfg, prefs.defaultFragmentationFlow.first())

            CoreServiceState.setSession(CoreServiceState.RunningSession(runtimeCfg, profileName, profileId = prefs.currentProfileIdFlow.first()))
            currentRunningCfg.set(runtimeCfg)

            try {
                initStartup(vlessConfig, xrayConfig, profileName)
                mainSupervisor()
            } finally {
                currentRunningCfg.set(null)
                if (!userStopped.get() && isActive) {
                    finishFailedRun()
                }
            }
        }

        return START_STICKY
    }

    private fun initStartup(
        vlessConfig: com.wireturn.app.data.VlessConfig,
        xrayConfig: com.wireturn.app.data.XrayConfig,
        profileName: String?
    ) {
        if (AppLogsState.logs.value.isNotEmpty()) {
            AppLogsState.addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
        val safeProfileName = profileName?.take(50) ?: "-"
        AppLogsState.addLog(getString(R.string.log_core_start, safeProfileName))

        val kernelInfo = when (val k = currentRunningCfg.get()?.kernelConfig) {
            is KernelConfig.Turnable -> "Turnable (${k.config.selectedRouteId})"
            is KernelConfig.Olcrtc -> "Olcrtc (${k.config.provider})"
            is KernelConfig.Webdav -> "WebDAV"
            is KernelConfig.FreeTurn -> "FreeTurn (${k.config.peer})"
            is KernelConfig.Qwdtt -> "qWDTT (${k.config.peer})"
            is KernelConfig.Vpn -> "VPN: ${k.config.protocol}"


            else -> "-"
        }
        val xrayInfo = if (xrayConfig.enabled) {
            val isXrayVless = xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS
            "${xrayConfig.protocol.name}${if (isXrayVless && vlessConfig.isDualRoute) " (Dual-route)" else ""}"
        } else if (xrayConfig.routingEnabled) "Routing" else "Disabled"
        AppLogsState.addLog(getString(R.string.log_core_profile_summary, kernelInfo, xrayInfo))

        val isXrayVless = xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS
        val isDualRouteStart = xrayConfig.enabled && isXrayVless && vlessConfig.isDualRoute

        NotificationHelper.cancelErrorNotification(this)
        
        if (isDualRouteStart) {
            // В режиме Dual-route стартуем в паузе, чтобы не запускать бинарник зря
            AppLogsState.addLog(getString(R.string.log_core_suppressed))
            CoreServiceState.setStatus(CoreStatus.Suppressed)
            // null, not the literal text - the Suppressed branch in NotificationHelper already
            // derives the right text (connecting/direct route active) from current xrayState.
            CoreServiceState.setStatusText(null)
        } else {
            CoreServiceState.setStatus(CoreStatus.Starting)
        }

        userStopped.set(false)
        restartCount = 0
        lastKnownFailureReason = null
        CoreTileService.requestUpdate(this)

        registerNetworkCallback()
    }

    private fun observeConnectionPower() {
        serviceScope.launch {
            combine(AppPreferences(applicationContext).keepCpuAwakeFlow, CoreServiceState.status,
                VpnServiceState.state) { enabled, core, vpn ->
                com.wireturn.app.kernel.userWakeRequested(enabled, core, vpn)
            }.distinctUntilChanged().collectLatest { enabled ->
                if (enabled) com.wireturn.app.kernel.OperationWakeLock.holdUserRequested(this@CoreService)
            }
        }
        serviceScope.launch {
            combine(CoreServiceState.status, XrayServiceState.state, VpnServiceState.state,
                CoreServiceState.session) { core, xray, vpn, session ->
                com.wireturn.app.kernel.connectionWorkPending(core, xray, vpn) to session?.generation
            }.distinctUntilChanged().collectLatest { (pending, _) ->
                if (pending) com.wireturn.app.kernel.OperationWakeLock.run(this@CoreService, "connect") {
                    kotlinx.coroutines.awaitCancellation()
                }
            }
        }
    }

    private fun moveToForeground() {
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
            AppLogsState.addLog(getString(R.string.log_core_foreground_failed, e.message ?: "Unknown"))
        }
    }

    private suspend fun mainSupervisor() = coroutineScope {
        val prefs = AppPreferences(applicationContext)

        launch {
            combine(
                XrayServiceState.state,
                prefs.xrayConfigFlow,
                prefs.vlessConfigFlow,
                XrayServiceState.session
            ) { state, xray, vless, xraySession ->
                val isXrayVless = xray.protocol == com.wireturn.app.data.XrayConfiguration.VLESS

                // Используем снапшот работающего конфига Xray для определения режима Dual-route.
                // Это предотвращает преждевременный запуск бинарника при отключении Dual-route,
                // пока Xray еще не перезагружен с новыми настройками.
                val effectiveVless = if (state != XrayState.Idle) (xraySession?.vless ?: vless) else vless
                val isDualRoute = xray.enabled && isXrayVless && effectiveVless.isDualRoute

                if (isDualRoute) {
                    when (state) {
                        XrayState.DirectRoute -> {
                            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                                AppLogsState.addLog(getString(R.string.log_core_suppressed))
                                CoreServiceState.setStatus(CoreStatus.Suppressed)
                            }
                            updateNotification(getString(R.string.direct_route_active))
                        }
                        // XrayState.Running в режиме Dual-route означает использование туннеля (local route).
                        // Если мы были в паузе (Suppressed), пробуждаем бинарник.
                        XrayState.Running -> {
                            if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                                CoreServiceState.setStatus(CoreStatus.Connecting)
                            }
                        }
                        // Starting, Connecting — пробуждение туннеля при потере прямого
                        // маршрута обрабатывается в XrayService.handleDualRouteLog
                        else -> {}
                    }
                } else if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                    // Режим Dual-route был выключен — пробуждаем туннель немедленно
                    CoreServiceState.setStatus(CoreStatus.Connecting)
                }
            }.collect {}
        }

        launch {
            CoreServiceState.status.collect { status ->
                if (status is CoreStatus.Suppressed) {
                    stopBinaryProcessGracefully()
                }
            }
        }

        launch {
            prefs.sharedNetworkSettingsFlow.map { it.dns.bootstrap }.distinctUntilChanged().drop(1).collect {
                if (!userStopped.get() && currentRunningCfg.get()?.kernelVariant in setOf(KernelVariant.VPN, KernelVariant.WEBDAV)) {
                    stopBinaryProcessGracefully(forRestart = true)
                }
            }
        }

        // Hot-reload: следим за изменениями конфига туннельного бинарника
        launch {
            combine(prefs.clientConfigFlow, prefs.defaultFragmentationFlow) { config, defaults ->
                com.wireturn.app.data.kernel.VpnFragmentation.applyDefault(config.fillDefaults(), defaults)
            }.distinctUntilChanged()
                .drop(1) // пропускаем начальное значение — уже обработано в onStartCommand
                .collect { newCfgRaw ->
                    // A deliberate stop clears the active config right around when
                    // handleStopAction() runs - without this guard that races the real stop.
                    if (userStopped.get()) return@collect
                    val runningCfg = currentRunningCfg.get() ?: return@collect
                    val newCfg = newCfgRaw

                    if (!newCfg.isValid) {
                        val errorRes = newCfg.getValidationErrorResId() ?: R.string.error_settings_empty
                        CoreServiceState.setStatus(CoreStatus.Error(getString(errorRes)))
                        stopBinaryProcessGracefully()

                        delay(3_000.milliseconds)
                        if (isActive && !userStopped.get()) {
                            finishFailedRun()
                        }
                        return@collect
                    }

                    val binaryChanged = requiresBinaryRestart(runningCfg, newCfg)
                    currentRunningCfg.set(newCfg)
                    CoreServiceState.setSession(CoreServiceState.RunningSession(newCfg, prefs.currentProfileNameFlow.first().orEmpty(), profileId = prefs.currentProfileIdFlow.first()))
                    if (binaryChanged) {
                        // A config/profile switch is a deliberate new attempt, not the watchdog
                        // retrying a failure - drop any restart counter left over from before.
                        restartCount = 0
                        lastKnownFailureReason = null
                        CoreServiceState.setRestartAttempt(null)

                        val xrayConfig = prefs.xrayConfigFlow.first()
                        val vlessConfig = prefs.vlessConfigFlow.first()
                        val isDualRoute = xrayConfig.enabled &&
                            xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS &&
                            vlessConfig.isDualRoute
                        if (isDualRoute && CoreServiceState.status.value !is CoreStatus.Idle) {
                            AppLogsState.addLog(getString(R.string.log_core_dual_route_config_changed))
                            CoreServiceState.setStatus(CoreStatus.Suppressed)
                        } else {
                            AppLogsState.addLog(getString(R.string.log_core_config_changed))
                            CoreServiceState.setStatusText(null)
                            CoreServiceState.setStatus(CoreStatus.Stopping)
                            stopBinaryProcessGracefully(forRestart = true)
                        }
                    }
                }
        }

        while (isActive && !userStopped.get()) {
            com.wireturn.app.domain.awaitCoreSupervisorResume(CoreServiceState.status)
            // Stop publishes Stopping before cancelling this run; waking up must not
            // turn that transition into another native process start.
            if (!isActive || userStopped.get()) break

            val cfg = currentRunningCfg.get() ?: break
            val startTime = System.currentTimeMillis()
            // Reset so a stale reason from an earlier, unrelated cycle can't outlive it - only
            // this run's own handler (if any) should set it before the failure check below reads it.
            lastKnownFailureReason = null
            val startupSuccessful = runBinary(cfg)
            val duration = System.currentTimeMillis() - startTime
            
            if (userStopped.get()) break

            val restartWasPlanned = plannedRestart.getAndSet(false)

            // ПРОВЕРКА ПАУЗЫ: Если бинарник был убит супервизором для перехода в DirectRoute,
            // мы НЕ должны запускать логику вотчдога.
            if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                restartCount = 0
                continue
            }

            if (isNetworkMissingAndHandled()) {
                continue
            }

            if (restartWasPlanned) {
                restartCount = 0
                CoreServiceState.setRestartAttempt(null)
                CoreServiceState.setStatusText(null)
                CoreServiceState.setStatus(CoreStatus.Connecting)
                continue
            }

            val currentStatus = CoreServiceState.status.value
            if (!startupSuccessful || currentStatus is CoreStatus.Error) {
                if (currentStatus !is CoreStatus.Error) {
                    AppLogsState.addLog(getString(R.string.log_core_quick_exit, duration))
                    AppLogsState.addLog(getString(R.string.log_core_startup_failed))
                    CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_kernel_or_settings)))
                }
                delay(3_000.milliseconds)
                if (isActive && !userStopped.get()) {
                    finishFailedRun()
                }
                break
            }

            if (duration > 300_000) {
                restartCount = 0
            }
            restartCount++
            if (restartCount > MAX_RESTARTS) {
                AppLogsState.addLog(getString(R.string.log_core_watchdog_limit, MAX_RESTARTS))
                val errorMsg = lastKnownFailureReason ?: getString(R.string.core_failed)
                CoreServiceState.emitFailed(errorMsg)
                if (!AppLifecycleState.isAppInForeground.value) {
                    NotificationHelper.notifyError(this@CoreService, errorMsg)
                }
                finishFailedRun()
                break
            }

            CoreServiceState.setStatus(CoreStatus.Connecting)
            
            var baseDelay = if (duration > 30_000) 1000L else minOf(1000L * restartCount, 30_000L)
            if (isSlowConnection()) {
                AppLogsState.addLog(getString(R.string.log_core_slow_network_watchdog))
                baseDelay = maxOf(baseDelay, 5000L)
            }
            val delayMs = baseDelay + Random.nextLong(0, 500)
            
            AppLogsState.addLog(getString(R.string.log_core_watchdog_restart, delayMs, restartCount, MAX_RESTARTS))
            // Clears any stale text left over from the previous run (e.g. "Active") so the
            // restart-attempt text below isn't shadowed by it in the notification/tile.
            CoreServiceState.setStatusText(null)
            CoreServiceState.setRestartAttempt(CoreServiceState.RestartAttempt(restartCount, MAX_RESTARTS))

            delay(delayMs.milliseconds)
        }
    }

    private suspend fun runBinary(cfg: ClientConfig): Boolean = coroutineScope {

        if (CoreServiceState.status.value is CoreStatus.Error) return@coroutineScope false
        val vkRuntime = com.wireturn.app.vk.VkRuntime(this@CoreService)
        val vkAuthJob = Job(coroutineContext[Job])
        val vkAuthScope = CoroutineScope(coroutineContext + vkAuthJob)
        val vkPendingHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        CoreServiceState.endWorkers()
        val state = BinaryOutputState()
        var startedProc: Process? = null

        try {
            val vpn = (cfg.kernelConfig as? KernelConfig.Vpn)?.config
            val selected = vpn?.let(com.wireturn.app.data.kernel.VpnEnginePolicy::apply)
            currentCoroutineContext().ensureActive()
            val latestConfig = currentRunningCfg.get()
            if (userStopped.get() || latestConfig == null || requiresBinaryRestart(cfg, latestConfig)) return@coroutineScope false
            val preparedCfg = vkRuntime.prepare(if (selected == null) cfg else cfg.copy(kernelConfig = KernelConfig.Vpn(selected)))
            if (selected != null) {
                CoreServiceState.setStatusText("Ядро: ${selected.engineLabel}")
            }
            val sharedNetwork = AppPreferences(applicationContext).sharedNetworkSettingsFlow.first()
            val bootstrap = sharedNetwork.dns.bootstrap

            val chainPreferences = AppPreferences(applicationContext)
            com.wireturn.app.kernel.ChainCompatibility.validate(preparedCfg, chainPreferences.xrayConfigFlow.first(),
                chainPreferences.vlessConfigFlow.first(), com.wireturn.app.kernel.LocalProxyPorts.resolve(preparedCfg, chainPreferences.effectiveXraySettingsFlow.first()))
            val cmdArgs = buildCommandArgs(preparedCfg, bootstrap)
            if (selected != null && com.wireturn.app.kernel.SingBoxConfig.snellTfoSuppressed(selected)) {
                AppLogsState.addLog(getString(R.string.snell_tfo_log))
            }
            state.workerRun = CoreServiceState.beginWorkers(preparedCfg.kernelConfig)
            AppLogsState.addLog(getString(R.string.log_core_command, redactedCommandLog(cmdArgs, cfg)))

            val proc = withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(cmdArgs)
                    .directory(filesDir)
                    .redirectErrorStream(true)
                val env = builder.environment()
                val nativeLibDir = applicationInfo.nativeLibraryDir
                
                // Lets the spawned binary find shared libraries bundled as jniLibs.
                env["LD_LIBRARY_PATH"] = nativeLibDir
                
                // Add native libs to PATH just in case
                val currentPath = env["PATH"] ?: ""
                if (!currentPath.contains(nativeLibDir)) {
                    env["PATH"] = "$nativeLibDir:$currentPath"
                }

                // Force Go-based binaries to use internal resolver (often avoids IPv6 issues)
                if ((preparedCfg.kernelConfig as? KernelConfig.Vpn)?.config?.usesXray == true) {
                    // Android has no resolv.conf DNS listener at ::1:53. The NDK-built
                    // standalone Xray must resolve its server through Android's resolver.
                    env.remove("TUNA_BOOTSTRAP_DNS")
                    if (bootstrap.isNotBlank()) env["TUNA_BOOTSTRAP_DNS"] = com.wireturn.app.data.BootstrapDns.parse(bootstrap)!!.xrayAddress()
                    env["GODEBUG"] = if (bootstrap.isBlank()) "netdns=cgo" else "netdns=go"
                } else if (cfg.kernelVariant == KernelVariant.WEBDAV) {
                    env["GODEBUG"] = "netdns=cgo"
                } else if (cfg.goDnsGo) {
                    env["GODEBUG"] = "netdns=go"
                }

                // Bundled CA store so TLS verification doesn't depend on the device's
                // (possibly stale) system trust store — see ensureCaBundle().
                if (cfg.useCustomCerts) {
                    caBundlePath?.let { env["SSL_CERT_FILE"] = it }
                }

                // Same non-suspending stretch as start() - a suspension point in between risks
                // cancellation orphaning the freshly-spawned process with no reference to clean it up.
                builder.start().also {
                    startedProc = it
                    process.set(it)
                }
            }


            if (cfg.kernelVariant == KernelVariant.OLCRTC) {
                state.startupEmitted = true
                if (canUpdateConnectingStatus()) {
                    markConnecting()
                }
            }

            // Watchdog for connection timeout
            val connectionWatchdog = launch {
                CoreServiceState.status.collectLatest { status ->
                    if (status is CoreStatus.Connecting) {
                        delay(120_000.milliseconds)
                        AppLogsState.addLog(getString(R.string.log_core_connection_timeout))
                        state.startupEmitted = true
                        proc.destroy()
                    }
                }
            }

            try {
                drainProcessOutput(proc, ::sendSigTerm,
                    stopOnCancellation = { stopBinaryProcessGracefully() }) { rawLine ->
                    val line = AppLogsState.stripAnsi(rawLine)
                    if (cfg.kernelVariant == KernelVariant.QWDTT && line.startsWith("VK_AUTH_REQUIRED|")) {
                        val hash = line.substringAfter('|').trim()
                        val q = (preparedCfg.kernelConfig as KernelConfig.Qwdtt).config
                        if (q.accountAuth && hash in q.hashList && vkPendingHashes.add(hash)) vkAuthScope.launch {
                            try {
                            val result = com.wdtt.client.VkAuthWebViewManager.authenticate(this@CoreService, hash)
                            result.getOrNull()?.let { creds ->
                                if (process.get() === proc) synchronized(proc.outputStream) {
                                    proc.outputStream.write(com.wdtt.client.VkAuthWebViewManager.encodeTurnCredsPayload(hash, creds).toByteArray())
                                    proc.outputStream.flush()
                                }
                            }
                            } finally { vkPendingHashes.remove(hash) }
                        }
                        return@drainProcessOutput false
                    }
                    if (cfg.kernelVariant == KernelVariant.WEBDAV) {
                        com.wireturn.app.kernel.WebdavKernel.safeLog(line)?.let(AppLogsState::addLog)
                    } else if (cfg.kernelVariant == KernelVariant.QWDTT) {
                        if (!line.startsWith("CAPTCHA_SOLVE|") && !line.startsWith("VK_AUTH_REQUIRED|")) AppLogsState.addProtocolLog("qWDTT", line)
                    } else AppLogsState.addLog(line)
                    // Process was intentionally killed (hot-reload/stop) — log but don't update status
                    if (process.get() !== proc) return@drainProcessOutput false
                    processOutputLine(line, state, cfg)
                }
            } finally {
                connectionWatchdog.cancel()
            }

            val exitCode = withContext(Dispatchers.IO) {
                if (proc.waitFor(5, TimeUnit.SECONDS)) proc.exitValue() else -1
            }
            AppLogsState.addLog(getString(R.string.log_core_stopped, exitCode))

            val wasKilledIntentionally = process.get() == null
            if (!state.startupEmitted && !state.startupFailed && !wasKilledIntentionally && isActive) {
                CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_process_no_output, exitCode)))
            }

            !state.startupFailed
        } catch (_: InterruptedIOException) {
            true
        } catch (_: CancellationException) {
            true
        } catch (invalid: IllegalArgumentException) {
            CoreServiceState.setStatus(CoreStatus.Error(invalid.message ?: "Некорректные настройки подключения"))
            updateNotification(getString(R.string.error_connecting))
            handleProcessException(invalid)
            false
        } catch (e: Exception) {
            handleProcessException(e)
            false
        } finally {
            CoreServiceState.endWorkers(state.workerRun)
            CoreServiceState.setCaptchaSession(null)
            // NonCancellable: this finally often runs on an already-cancelled coroutine; without
            // it the IO hop below would throw immediately and leak the old binary process.
            withContext(NonCancellable) {
                stopBinaryProcessGracefully()
                // compareAndSet: if process already points to a NEWER run's process by now, an
                // unconditional set(null) would clear that instead and leak it.
                process.compareAndSet(startedProc, null)
                vkAuthJob.cancel()

            }
        }
    }

    private suspend fun processOutputLine(line: String, state: BinaryOutputState, cfg: ClientConfig): Boolean {
        CoreServiceState.workerLog(state.workerRun, line)
        val lower = line.lowercase()
        return KernelRegistry.get(cfg.kernelVariant).parseLogLine(line, lower, state, logContext, cfg)
    }

    // MainActivity's own LaunchedEffect(captchaSession) would eventually pick a new session up
    // too, but don't rely solely on that recomposing in time - open the window directly while
    // the app is foreground. Shared by every kernel's captcha flow (see kernel/*/*.kt).
    private fun launchCaptchaActivityIfForeground(url: String) {
        if (!AppLifecycleState.isAppInForeground.value) return
        val intent = Intent(this, com.wireturn.app.ui.activities.CaptchaActivity::class.java).apply {
            putExtra("CAPTCHA_URL", url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // FreeTurn/Qwdtt have no file-based config option upstream (unlike the other
    // kernels), so their sensitive values ride the process command line directly - mask those
    // specific flags for the app's own log, which the user may end up sharing for support.
    private fun redactedCommandLog(cmdArgs: List<String>, cfg: ClientConfig): String {
        val sensitiveFlags = KernelRegistry.get(cfg.kernelVariant).sensitiveCommandFlags
        if (sensitiveFlags.isEmpty()) return cmdArgs.joinToString(" ")
        return CommandLogRedactor.redact(cmdArgs, sensitiveFlags)
    }

    private fun buildCommandArgs(cfg: ClientConfig, bootstrap: String): List<String> {
        val snapshotContext = object : KernelCommandContext by commandContext {
            override val bootstrapDns = bootstrap
        }
        return KernelRegistry.get(cfg.kernelVariant).buildCommand(snapshotContext, cfg)
    }


    private fun requiresBinaryRestart(old: ClientConfig, new: ClientConfig): Boolean {
        if (old.kernelConfig != new.kernelConfig) return true
        if (old.goDnsGo != new.goDnsGo) return true
        if (old.useCustomCerts != new.useCustomCerts) return true
        return when (new.kernelConfig) {
            is KernelConfig.Turnable -> old.listenAddr != new.listenAddr
            is KernelConfig.Olcrtc ->
                old.socksAddr != new.socksAddr ||
                old.isSocksAuthEnabled != new.isSocksAuthEnabled ||
                old.socksUser != new.socksUser ||
                old.socksPass != new.socksPass
            is KernelConfig.Webdav ->
                old.socksAddr != new.socksAddr ||
                old.isSocksAuthEnabled != new.isSocksAuthEnabled ||
                old.socksUser != new.socksUser ||
                old.socksPass != new.socksPass
            is KernelConfig.FreeTurn -> old.listenAddr != new.listenAddr
            is KernelConfig.Qwdtt ->
                old.listenAddr != new.listenAddr ||
                old.socksAddr != new.socksAddr ||
                old.isSocksAuthEnabled != new.isSocksAuthEnabled ||
                old.socksUser != new.socksUser ||
                old.socksPass != new.socksPass
            is KernelConfig.MasterDns, is KernelConfig.Vpn ->
                old.socksAddr != new.socksAddr || old.isSocksAuthEnabled != new.isSocksAuthEnabled ||
                old.socksUser != new.socksUser || old.socksPass != new.socksPass
        }
    }

    private fun handleProcessException(e: Exception) {
        AppLogsState.addLog(getString(R.string.error_critical_format, e.message))
    }

    private suspend fun stopBinaryProcessGracefully(forRestart: Boolean = false) = stopMutex.withLock {
        // Mark only an actual running core/pool, under the same lock as its removal.
        // A late network callback with no process must not mask a future startup failure.
        if (forRestart && (process.get() != null)) plannedRestart.set(true)

        val proc = process.getAndSet(null) ?: return@withLock
        terminateCoreProcess(proc, ::sendSigTerm,
            forceTerminate = { com.wireturn.app.kernel.signalAndroidProcess(it, 9) })
    }

    private fun sendSigTerm(proc: Process) {
        com.wireturn.app.kernel.signalAndroidProcess(proc, 15)
    }

    private fun startXraySupervisor() {
        xraySupervisorJob?.cancel()
        xraySupervisorJob = serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            val configFlow = combine(
                prefs.xrayConfigFlow,
                prefs.vlessConfigFlow,
                prefs.wgConfigFlow,
                prefs.effectiveXraySettingsFlow,
                prefs.vpnSettingsFlow.map { it.enabled }.distinctUntilChanged()
            ) { xray, vless, wg, settings, hevEnabled ->
                XrayConfigSignals(xray, vless.fillDefaults(), wg.fillDefaults(), settings.fillDefaults(), hevEnabled)
            }
            combine(
                configFlow,
                CoreServiceState.status,
                XrayServiceState.state,
                CoreServiceState.session
            ) { signals, status, xrayState, coreSession ->
                val clientConfig = coreSession?.clientConfig ?: return@combine null

                val shouldBeRunning = signals.xrayConfig.isRequired &&
                        status !is CoreStatus.Idle &&
                        status !is CoreStatus.Error &&
                        status !is CoreStatus.WaitingForNetwork

                val connectionTarget = if (clientConfig.kernelVariant.isSocks5Native) clientConfig.socksAddr else clientConfig.listenAddr
                val connectionAuth = if (clientConfig.kernelVariant.isSocks5Native) {
                    Triple(clientConfig.isSocksAuthEnabled, clientConfig.socksUser, clientConfig.socksPass)
                } else null

                XraySupervisorBundle(
                    shouldBeRunning = shouldBeRunning,
                    xrayState = xrayState,
                    signals = signals,
                    kernelVariant = clientConfig.kernelVariant,
                    connectionTarget = connectionTarget,
                    connectionAuth = connectionAuth
                )
            }
            .filterNotNull()
            .distinctUntilChanged { old, new ->
                if (old.shouldBeRunning != new.shouldBeRunning) return@distinctUntilChanged false
                if (!new.shouldBeRunning) return@distinctUntilChanged new.xrayState == XrayState.Idle
                return@distinctUntilChanged new.xrayState != XrayState.Idle &&
                    old.signals == new.signals &&
                    old.kernelVariant == new.kernelVariant &&
                    old.connectionTarget == new.connectionTarget &&
                    old.connectionAuth == new.connectionAuth
            }
            .collectLatest { data: XraySupervisorBundle ->
                val needsStart = data.shouldBeRunning && data.xrayState == XrayState.Idle
                val needsStop = !data.shouldBeRunning && data.xrayState != XrayState.Idle
                val needsRestart = data.shouldBeRunning && data.xrayState != XrayState.Idle

                if (needsRestart) {
                    AppLogsState.addLog(getString(R.string.log_xray_config_change_restart))
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@CoreService, XrayService::class.java))
                        delay(500.milliseconds)
                        startForegroundService(Intent(this@CoreService, XrayService::class.java))
                    }
                } else if (needsStart) {
                    delay(500.milliseconds) // Debounce during profile switches
                    withContext(Dispatchers.Main) {
                        val currentXrayState = XrayServiceState.state.value
                        val currentStatus = CoreServiceState.status.value
                        if (currentXrayState == XrayState.Idle && currentStatus !is CoreStatus.Idle && currentStatus !is CoreStatus.Error) {
                            startForegroundService(Intent(this@CoreService, XrayService::class.java))
                        }
                    }
                } else if (needsStop) {
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@CoreService, XrayService::class.java))
                    }
                }
            }
        }
    }

    // OLCRTC/WEBDAV run their own local SOCKS5 listener, so VPN mode can target those directly
    // when Xray isn't in the picture. Xray keeps priority when running, since it may itself be
    // wrapping one of those cores as a front proxy (see startXraySupervisor() above).
    private data class VpnTarget(val addr: String, val user: String?, val pass: String?)

    // terminalError: the core isn't coming back on its own (watchdog gave up / invalid config),
    // as opposed to a transient gap (mid-retry, switching profiles) that's expected to recover.
    private data class VpnTargetSignal(val target: VpnTarget?, val terminalError: Boolean)

    private data class VpnSupervisorBundle(
        val signal: VpnTargetSignal,
        val vpnSettings: VpnSettings,
        val vpnState: VpnState
    )

    private fun startVpnSupervisor() {
        vpnSupervisorJob?.cancel()
        vpnSupervisorJob = serviceScope.launch {
            val supervisorScope = this
            val prefs = AppPreferences(applicationContext)
            var lastVpnSettings: VpnSettings? = null
            var lastTarget: VpnTarget? = null
            // Upstream loss retains the established VPN route until explicit Stop.

            // Retries a failed establish() (e.g. another app still held VPN ownership) on a
            // fixed backoff - without this, VpnState.Error only ever gets retried when settings
            // or target happen to change, and can otherwise sit there forever.
            var pendingVpnRetryJob: Job? = null

            fun stopIntent() = Intent(this@CoreService, HevVpnService::class.java).apply {
                action = HevVpnService.ACTION_STOP
            }
            fun startIntent(target: VpnTarget) = Intent(this@CoreService, HevVpnService::class.java).apply {
                putExtra(HevVpnService.EXTRA_SOCKS5_ADDR, target.addr)
                if (target.user != null) {
                    putExtra(HevVpnService.EXTRA_SOCKS5_USER, target.user)
                    putExtra(HevVpnService.EXTRA_SOCKS5_PASS, target.pass)
                }
            }
            fun retargetIntent(target: VpnTarget) = startIntent(target).apply {
                action = HevVpnService.ACTION_UPDATE_TARGET
            }
            fun retryIntent(target: VpnTarget) = if (VpnServiceState.tunEstablished) retargetIntent(target) else startIntent(target)

            val targetFlow = combine(
                XrayServiceState.state,
                XrayServiceState.session,
                CoreServiceState.status,
                CoreServiceState.session,
                prefs.xrayConfigFlow
            ) { xrayState, xraySession, coreStatus, coreSession, routingConfig ->
                if (xrayState != XrayState.Idle && xraySession != null) {
                    val s = xraySession.settings
                    VpnTargetSignal(
                        VpnTarget(
                            s.connectableAddress,
                            s.proxyUser.takeIf { s.isProxyAuthEnabled && it.isNotBlank() },
                            s.proxyPass
                        ),
                        terminalError = false
                    )
                } else if (!routingConfig.isRequired && coreSession != null &&
                    coreSession.clientConfig.kernelVariant.isSocks5Native &&
                    coreStatus !is CoreStatus.Idle && coreStatus !is CoreStatus.Error && coreStatus !is CoreStatus.WaitingForNetwork
                ) {
                    val cc = coreSession.clientConfig
                    VpnTargetSignal(
                        VpnTarget(
                            // socksAddr may be bound to 0.0.0.0; hev needs a literal destination.
                            cc.socksAddr.replace("0.0.0.0:", "127.0.0.1:"),
                            cc.socksUser.takeIf { cc.isSocksAuthEnabled && it.isNotBlank() },
                            cc.socksPass
                        ),
                        terminalError = false
                    )
                } else {
                    // Target-less and no Xray: mid-retry states are still expected to recover,
                    // terminal errors retain the established blocking TUN too.
                    VpnTargetSignal(null, terminalError = coreStatus is CoreStatus.Error)
                }
            }

            combine(targetFlow, prefs.vpnSettingsFlow, VpnServiceState.state) { signal, vpnSettings, vpnState ->
                VpnSupervisorBundle(signal, vpnSettings, vpnState)
            }.collect { bundle ->
                withContext(Dispatchers.Main) {
                    val target = bundle.signal.target
                    val settingsChanged = lastVpnSettings != null && lastVpnSettings != bundle.vpnSettings
                    val targetChanged = lastTarget != null && lastTarget != target
                    lastVpnSettings = bundle.vpnSettings
                    lastTarget = target

                    if (!bundle.vpnSettings.enabled) {
                        // Explicit user opt-out - stop immediately, no grace period.
                        if (bundle.vpnState != VpnState.Idle) startService(stopIntent())
                        return@withContext
                    }

                    if (target == null) {
                        // Keep the existing TUN on both retry gaps and terminal failures.
                        // An unavailable SOCKS upstream blocks traffic; releasing TUN would leak DIRECT.
                        pendingVpnRetryJob?.cancel()
                        pendingVpnRetryJob = null
                        return@withContext
                    }
                    val vpnRunning = bundle.vpnState == VpnState.Running
                    val vpnError = bundle.vpnState is VpnState.Error

                    if (!vpnError) {
                        pendingVpnRetryJob?.cancel()
                        pendingVpnRetryJob = null
                    }

                    when {
                        bundle.vpnState == VpnState.Idle -> {
                            // Establish as soon as any target exists, before it's even connected -
                            // a kill switch should be up before it's needed.
                            if (VpnServiceState.state.value != VpnState.Starting) {
                                startService(startIntent(target))
                            }
                        }
                        vpnError && (settingsChanged || targetChanged) -> {
                            AppLogsState.addLog(getString(R.string.log_vpn_restarting_config))
                            if (settingsChanged) {
                                startService(stopIntent())
                                startService(startIntent(target))
                            } else startService(retryIntent(target))
                        }
                        vpnError && pendingVpnRetryJob == null -> {
                            // Nothing about the target/settings changed, so nothing else here
                            // will ever retry this on its own - back off and try again anyway.
                            pendingVpnRetryJob = supervisorScope.launch {
                                delay(VPN_ERROR_RETRY_MS.milliseconds)
                                pendingVpnRetryJob = null
                                withContext(Dispatchers.Main) {
                                    if (VpnServiceState.state.value is VpnState.Error) {
                                        startService(retryIntent(target))
                                    }
                                }
                            }
                        }
                        vpnRunning && settingsChanged -> {
                            // Routing config itself changed (filtering/bypass/app list) - that's
                            // baked into the Builder at establish() time, so it must be redone.
                            AppLogsState.addLog(getString(R.string.log_vpn_restarting_config))
                            startService(stopIntent())
                            startService(startIntent(target))
                        }
                        vpnRunning && targetChanged -> {
                            // Only the upstream SOCKS5 hop moved (e.g. switched profiles) - hot-
                            // swap the relay's target without a fresh establish(), so the tun
                            // interface (and the device's default network) never blips.
                            startService(retargetIntent(target))
                        }
                    }
                }
            }
        }
    }

    private data class XrayConfigSignals(
        val xrayConfig: com.wireturn.app.data.XrayConfig,
        val vless: com.wireturn.app.data.VlessConfig,
        val wg: com.wireturn.app.data.WgConfig,
        val settings: com.wireturn.app.data.XraySettings,
        val hevEnabled: Boolean
    )

    private data class XraySupervisorBundle(
        val shouldBeRunning: Boolean,
        val xrayState: XrayState,
        val signals: XrayConfigSignals,
        val kernelVariant: KernelVariant,
        val connectionTarget: String?,
        val connectionAuth: Triple<Boolean, String, String>?
    )


    private fun observeErrorForNotification() {
        serviceScope.launch {
            combine(
                CoreServiceState.status,
                AppLifecycleState.isAppInForeground
            ) { status, isForeground ->
                status to isForeground
            }.collect { (status, isForeground) ->
                if (status is CoreStatus.Error && !isForeground) {
                    NotificationHelper.notifyError(this@CoreService, status.message)
                } else if (status is CoreStatus.Connected || status is CoreStatus.Suppressed || status is CoreStatus.WaitingForNetwork || isForeground) {
                    NotificationHelper.cancelErrorNotification(this@CoreService)
                }
            }
        }
    }

    private fun observeCaptchaForNotification() {
        serviceScope.launch {
            combine(
                CoreServiceState.captchaSession,
                AppLifecycleState.isAppInForeground
            ) { session, isForeground ->
                session to isForeground
            }.collect { (session, isForeground) ->
                if (session != null && !isForeground) {
                    delay(1_000.milliseconds)
                    if (CoreServiceState.captchaSession.value != null && !AppLifecycleState.isAppInForeground.value) {
                        NotificationHelper.notifyCaptcha(this@CoreService, session.url)
                    }
                } else {
                    NotificationHelper.cancelCaptchaNotification(this@CoreService)
                }
            }
        }
    }

    // Bridges the UI-solved captcha token back into the qWDTT process's stdin. Only QwdttKernel
    // calls ctx.setPendingCaptchaSessionId() (see kernel/QwdttKernel.kt), so this is a no-op for
    // every other kernel's captcha sessions (FreeTurn detects its own success from its log output,
    // never emits here).
    private fun observeQwdttCaptchaResult() {
        serviceScope.launch {
            CoreServiceState.captchaResult.collect { (sessionId, token) ->
                // compareAndSet: only the matching, still-pending session acts, and it atomically
                // clears itself first so the dismiss-watcher below can't also treat the
                // setCaptchaSession(null) this causes as a cancellation.
                if (pendingQwdttCaptchaSessionId.compareAndSet(sessionId, -1L)) {
                    writeQwdttStdin("CAPTCHA_RESULT|$token")
                    CoreServiceState.setCaptchaSession(null)
                }
            }
        }
        serviceScope.launch {
            CoreServiceState.captchaSession.collect { session ->
                if (session == null) {
                    val previous = pendingQwdttCaptchaSessionId.getAndSet(-1L)
                    if (previous != -1L) {
                        writeQwdttStdin("CAPTCHA_RESULT|error:cancelled")
                    }
                }
            }
        }
    }

    private fun writeQwdttStdin(line: String) {
        try {
            process.get()?.outputStream?.let {
                it.write((line + "\n").toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        physicalNetworkState.clear()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (networkCallback !== this) return
                if (physicalNetworkState.available(network.networkHandle) !=
                    com.wireturn.app.domain.PhysicalNetworkState.Change.CHANGED) return
                
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    val prefs = AppPreferences(applicationContext)
                    if (!prefs.restartOnNetworkChangeFlow.first()) return@launch

                    delay(2_000.milliseconds)
                    if (!userStopped.get() && (process.get() != null)) {
                        AppLogsState.addLog(getString(R.string.log_core_network_change))
                        updateNotification(getString(R.string.notification_network_change))
                        restartCount = 0
                        stopBinaryProcessGracefully(forRestart = true)
                    }
                }
            }

            override fun onLost(network: Network) {
                if (networkCallback !== this) return
                physicalNetworkState.lost(network.networkHandle)
                if (!physicalNetworkState.hasNetwork()) networkDebounceJob?.cancel()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCallback !== this) return
                val eligible = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (!physicalNetworkState.capabilities(network.networkHandle, eligible)) return
                if (CoreServiceState.status.value is CoreStatus.WaitingForNetwork) {
                    if (eligible) {
                        networkDebounceJob?.cancel()
                        // Сеть появилась — переходим в Starting, чтобы не сработать повторно, и запускаемся
                        coreCommands.cancelBeforeStart { CoreServiceState.setStatus(CoreStatus.Starting) }
                        val intent = Intent(this@CoreService, CoreService::class.java)
                        startService(intent)
                    }
                }
            }
        }
        networkCallback = cb
        
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        // Observe the best physical network, not every available Wi-Fi/cellular
        // network. A secondary network appearing must not restart a healthy VPN.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cm.registerBestMatchingNetworkCallback(request, cb, handler)
        } else {
            cm.requestNetwork(request, cb, handler)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback
        networkCallback = null
        physicalNetworkState.clear()
        callback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
    }

    private suspend fun finishFailedRun() {
        val enabled = AppPreferences(applicationContext).vpnSettingsFlow.first().enabled
        if (com.wireturn.app.domain.VpnFailurePolicy.retainTun(enabled, VpnServiceState.tunEstablished, userStopped.get())) {
            if (CoreServiceState.status.value !is CoreStatus.Error) {
                CoreServiceState.setStatus(CoreStatus.Error("Соединение потеряно. Трафик заблокирован до переподключения или остановки VPN."))
            }
            // Keep the foreground owner and Stop action alive; another Start can recover.
            NotificationHelper.updateNotification(this)
        } else withContext(Dispatchers.Main) { stopSelf() }
    }
    private fun handleStopAction(disableAutoLaunch: Boolean) {
        if (userStopped.getAndSet(true)) return
        val retiringObservers = listOfNotNull(xraySupervisorJob, vpnSupervisorJob, networkDebounceJob)
        retiringObservers.forEach { it.cancel() }
        xraySupervisorJob = null
        vpnSupervisorJob = null
        networkDebounceJob = null
        NotificationHelper.cancelErrorNotification(this)
        CoreServiceState.setStatus(CoreStatus.Stopping)
        NotificationHelper.updateNotification(this)
        coreCommands.stop({ stopBinaryProcessGracefully() }, {
            retiringObservers.forEach { it.join() }
            if (disableAutoLaunch) {
                val prefs = AppPreferences(applicationContext)
                val autoLaunch = prefs.autoLaunchSettingsFlow.first()
                if (autoLaunch.enabled) {
                    prefs.updateAutoLaunchSettings(autoLaunch.copy(enabled = false))
                }
            }
            
            // Explicitly stop Xray and VPN mode when tunnel stops
            withContext(Dispatchers.Main) {
                stopService(Intent(this@CoreService, XrayService::class.java))
                startService(Intent(this@CoreService, HevVpnService::class.java).apply {
                    action = HevVpnService.ACTION_STOP
                })
            }

            stopBinaryProcessGracefully()
        }, {
            withContext(Dispatchers.Main) {
                CoreServiceState.setStatus(CoreStatus.Idle)

                NotificationHelper.updateNotification(this@CoreService)
                stopSelf()
            }
        })
    }

    private suspend fun isNetworkMissingAndHandled(): Boolean {
        if (!isNetworkAvailable()) {
            val prefs = AppPreferences(applicationContext)
            if (prefs.waitForNetworkFlow.first()) {
                if (CoreServiceState.status.value !is CoreStatus.WaitingForNetwork) {
                    AppLogsState.addLog(getString(R.string.log_core_no_network_waiting))
                }
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.WaitingForNetwork)
                    updateNotification(getString(R.string.status_waiting_for_network))
                }
                return true
            }
        }
        return false
    }

    private fun isNetworkAvailable(): Boolean {
        return physicalNetworkState.hasNetwork()
    }

    private suspend fun getNetworkQuality(): NetworkQuality = withContext(Dispatchers.IO) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@withContext NetworkQuality.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return@withContext NetworkQuality.OFFLINE
        
        // 1. Порог скорости для сотовых сетей снижаем до минимума, так как система часто ошибается
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val speed = caps.linkDownstreamBandwidthKbps
            if (speed in 1..150) return@withContext NetworkQuality.SLOW
        }
        
        // 2. Проверка реальной задержки через TCP-соединение с max.ru (гарантированно доступен в РФ)
        try {
            val start = System.currentTimeMillis()
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("max.ru", 80), 1500)
            }
            val rtt = System.currentTimeMillis() - start
            // Если ответ шел дольше 800мс — считаем сеть медленной для watchdog
            if (rtt > 800) NetworkQuality.SLOW else NetworkQuality.FAST
        } catch (_: Exception) {
            // Если max.ru недоступен совсем, это не "медленная сеть", а отсутствие интернета
            NetworkQuality.OFFLINE
        }
    }

    private suspend fun isSlowConnection(): Boolean = getNetworkQuality() == NetworkQuality.SLOW

    private fun updateNotification(text: String) {
        CoreServiceState.setStatusText(text)
    }

    override fun onDestroy() {
        super.onDestroy()
        isStarted.set(false)
        userStopped.set(true)
        currentRunningCfg.set(null)
        
        CoreServiceState.setStatus(CoreStatus.Idle)

        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        AppLogsState.addLog(getString(R.string.log_core_stop_ui))

        // Safety net: xraySupervisorJob/vpnSupervisorJob normally tear these down reactively, but
        // they live on serviceScope, which is cancelled below - if the service dies before they
        // get to react (e.g. watchdog-exhausted path setting Idle right as it fires), HevVpnService
        // can be left orphaned with the system VPN/TUN still established. Stop both unconditionally
        // here so that never depends on supervisor timing.
        stopService(Intent(this, XrayService::class.java))
        if (VpnServiceState.state.value != VpnState.Idle) {
            startService(Intent(this, HevVpnService::class.java).apply {
                action = HevVpnService.ACTION_STOP
            })
        }

        coreCommands.stop({ stopBinaryProcessGracefully() }, {}, {

            NotificationHelper.updateNotification(this@CoreService)
            serviceScope.cancel()
        })

    }

    companion object {
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STOP_BY_USER = "ACTION_STOP_BY_USER"
        const val MAX_RESTARTS = 10

        private const val VPN_ERROR_RETRY_MS = 15_000L

        fun start(context: Context, cfg: ClientConfig) {
            cfg.fillDefaults().getValidationErrorResId()?.let { errorRes ->
                CoreServiceState.setStatus(CoreStatus.Error(context.getString(errorRes)))
                return
            }
            // A parked old supervisor must remain asleep until onStartCommand cancels it.
            // Idle/Error still publish promptly so UI knows a new attempt began.
            if (!com.wireturn.app.domain.isCoreSupervisorParked(CoreServiceState.status.value)) {
                CoreServiceState.setStatus(CoreStatus.Starting)
            }
            // A deliberate new attempt, not the watchdog retrying - drop any stale restart count.
            CoreServiceState.setRestartAttempt(null)
            context.startForegroundService(Intent(context, CoreService::class.java))
        }

        fun stop(context: Context, byUser: Boolean = false) {
            val intent = Intent(context, CoreService::class.java).apply {
                action = if (byUser) ACTION_STOP_BY_USER else ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
