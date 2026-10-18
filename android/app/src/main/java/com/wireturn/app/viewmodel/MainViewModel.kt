package com.wireturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.wireturn.app.AppLogsState
import com.wireturn.app.CoreService
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.CoreTileService
import com.wireturn.app.HevSocks5Tunnel
import com.wireturn.app.R
import com.wireturn.app.VpnServiceState
import com.wireturn.app.XrayService
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.AutoLaunchSettings
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.listCategory
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.data.kernel.FreeTurnConfig
import com.wireturn.app.data.kernel.OlcrtcConfig
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.data.kernel.TurnableConfig
import com.wireturn.app.data.kernel.WebdavConfig
import com.wireturn.app.domain.AppUpdater
import com.wireturn.app.domain.CoreManager
import com.wireturn.app.domain.ProfileManager
import com.wireturn.app.domain.activeLocalSocksProxy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Proxy
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val historyStore = com.wireturn.app.domain.ProbeHistoryStore.get(application)

    private suspend fun stopManualProbes() {
        profileUrlTestJob?.cancelAndJoin()
    }

    /** Explicit connect buttons differ from selecting a profile for the next start. */
    fun connectProfile(id: String, onCompletion: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                stopManualProbes()
                groupRunner.stopAndJoin()
                val profile = prefs.profilesFlow.first().singleOrNull { it.id == id } ?: return@launch
                if (prefs.activateSelectedProfile(profile) == null) return@launch
                startCoreInternal(forceRestart = CoreServiceState.isRunning.value)
                onCompletion()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                android.widget.Toast.makeText(getApplication(), "Не удалось подключить профиль. Проверьте его настройки.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun connectGroup(group: com.wireturn.app.data.ConnectionGroup, onCompletion: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                stopManualProbes()
                groupRunner.stopAndJoin()
                val current = prefs.connectionGroupsFlow.first().singleOrNull { it.id == group.id } ?: return@launch
                current.validateForTesting(prefs.profilesFlow.first())
                prefs.setActiveConnectionGroup(current.id)
                groupRunner.connect(current)
                onCompletion()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                android.widget.Toast.makeText(getApplication(), "Не удалось подключить группу. Проверьте её состав и настройки.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private val profileUrlTests = com.wireturn.app.domain.ProfileUrlTestBatch(onCompleted = { result ->
        val latency = result.label.removeSuffix(" мс").toLongOrNull()
        historyStore.record(com.wireturn.app.domain.SavedProbeResult(result.snapshot.id, com.wireturn.app.domain.ProbeKind.URL,
            result.snapshot.configDigest, requireNotNull(result.measuredAt), latencyMs = latency,
            error = if (latency == null) result.label else null))
    })
    val profileUrlTestState = profileUrlTests.state
    private var profileUrlTestJob: Job? = null

    fun cancelProfileUrlTests() { profileUrlTestJob?.cancel() }

    fun testProfileUrls(owner: String, candidates: List<Profile>) {
        if (profileUrlTestState.value.busy || profileUrlTestJob?.isCompleted == false || groupRunner.hasPendingWork) return
        // Account-backed bypass profiles may only use their own active session.
        val snapshot = candidates.filter { manualProbeAllowed(it) }.associateBy { it.id }
        if (snapshot.isEmpty()) return
        profileUrlTestJob = viewModelScope.launch {
            val isolated = com.wireturn.app.domain.IsolatedProfileTest(getApplication())
            val test = com.wireturn.app.data.ConnectionGroup(
                id = "manual-url-test", name = "URL-test",
                category = com.wireturn.app.data.ConnectionCategory.VPN,
                type = com.wireturn.app.data.GroupTestType.URL_TEST,
                memberIds = snapshot.keys.toList(), samples = 1)
            profileUrlTests.run(owner, snapshot.values.toList(), { profiles.value },
                getApplication<Application>().getSharedPreferences("url_test", 0).getInt("parallelism", 4),
                eligible = { manualProbeAllowed(it) }) { profile ->
                isolated.withTunnel(profile) { config ->
                    com.wireturn.app.domain.GroupProbe.measure(test, profile.id, config).latencyMs
                }
            }
        }
    }
    private fun manualProbeAllowed(profile: Profile): Boolean {
        if (com.wireturn.app.domain.ProbeEligibility.allows(profile)) return true
        if (CoreServiceState.session.value?.profileId == profile.id &&
            CoreServiceState.status.value == com.wireturn.app.CoreStatus.Connected) return true
        android.widget.Toast.makeText(getApplication(), "Сначала подключите этот сервер для тестирования", android.widget.Toast.LENGTH_LONG).show()
        return false
    }

    private val coreManager = CoreManager(application)
    private val groupRunner = com.wireturn.app.domain.ConnectionGroupRunner.get(application)
    val groupRunState = groupRunner.state.asStateFlow()
    fun cancelGroupTest() = groupRunner.release()
    val connectionGroups = prefs.connectionGroupsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeConnectionGroupId = prefs.activeConnectionGroupFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun selectConnectionGroup(group: com.wireturn.app.data.ConnectionGroup, onCompletion: () -> Unit = {}) {
        if (!com.wireturn.app.domain.ManualGroupPolicy.allows(group)) return
        groupRunner.release()
        viewModelScope.launch {
            group.validate(profiles.value)
            prefs.setActiveConnectionGroup(group.id)
            if (CoreServiceState.isRunning.value) groupRunner.connect(group)
            onCompletion()
        }
    }
    fun selectGroupMember(group: com.wireturn.app.data.ConnectionGroup, id: String) {
        if (com.wireturn.app.domain.ManualGroupPolicy.allows(group)) groupRunner.selectMember(group, id)
    }

    fun runConnectionGroup(group: com.wireturn.app.data.ConnectionGroup) {
        if (profileUrlTestJob?.isCompleted == false) return
        if (com.wireturn.app.domain.ManualGroupPolicy.allows(group)) groupRunner.test(group)
    }
    fun testConnectionGroup(group: com.wireturn.app.data.ConnectionGroup, type: com.wireturn.app.data.GroupTestType) {
        if (type != com.wireturn.app.data.GroupTestType.URL_TEST || group.type != type) return
        runConnectionGroup(group)
    }
    fun stopGroupSelection() {
        groupRunner.release()
        viewModelScope.launch { prefs.setActiveConnectionGroup(null) }
    }
    fun saveConnectionGroup(group: com.wireturn.app.data.ConnectionGroup) {
        require(com.wireturn.app.domain.ManualGroupPolicy.allows(group)) {
            "Группы URL-test доступны только в разделе VPN"
        }
        group.validateForTesting(profiles.value)
        if (groupRunState.value.groupId == group.id) groupRunner.release()
        viewModelScope.launch {
            prefs.saveConnectionGroups(connectionGroups.value.filterNot { it.id == group.id } + group)
        }
    }
    fun deleteConnectionGroup(id: String) {
        if (groupRunState.value.groupId == id) groupRunner.release()
        viewModelScope.launch {
            if (prefs.activeConnectionGroupFlow.first() == id) prefs.setActiveConnectionGroup(null)
            prefs.saveConnectionGroups(connectionGroups.value.filterNot { it.id == id })
        }
    }
    private val appUpdater = AppUpdater(application)
    private val geoAssetsManager = com.wireturn.app.domain.GeoAssetsManager(application)
    private val profileManager = ProfileManager.get(application)

    val coreState: StateFlow<CoreState> = coreManager.coreState
    val logs: StateFlow<List<AppLogsState.LogEntry>> = AppLogsState.logs
    val updateState: StateFlow<UpdateState> = appUpdater.state
    val updateProgress: StateFlow<Int> = appUpdater.downloadProgress
    val geoAssetsState: StateFlow<GeoAssetsState> = geoAssetsManager.state
    val geoAssetsProgress: StateFlow<Int> = geoAssetsManager.downloadProgress
    val geoAssetsInstalledTick: StateFlow<Int> = geoAssetsManager.installedTick

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private val _interfaceStyle = MutableStateFlow(com.wireturn.app.data.InterfaceStyle.ORBIT)
    val interfaceStyle = _interfaceStyle.asStateFlow()
    fun setInterfaceStyle(style: com.wireturn.app.data.InterfaceStyle) {
        viewModelScope.launch { prefs.setInterfaceStyle(style) }
    }
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicTheme = MutableStateFlow(true)
    val dynamicTheme: StateFlow<Boolean> = _dynamicTheme.asStateFlow()

    private val _clientConfig = MutableStateFlow(ClientConfig())
    val clientConfig: StateFlow<ClientConfig> = _clientConfig.asStateFlow()

    private val _batteryNotificationDismissed = MutableStateFlow(false)
    val batteryNotificationDismissed: StateFlow<Boolean> = _batteryNotificationDismissed.asStateFlow()

    private val _appsExclusionHintShown = MutableStateFlow(false)
    val appsExclusionHintShown: StateFlow<Boolean> = _appsExclusionHintShown.asStateFlow()

    private val _allowUnstableUpdates = MutableStateFlow(false)
    val allowUnstableUpdates: StateFlow<Boolean> = _allowUnstableUpdates.asStateFlow()

    private val _waitForNetwork = MutableStateFlow(true)
    val waitForNetwork: StateFlow<Boolean> = _waitForNetwork.asStateFlow()

    private val _restartOnNetworkChange = MutableStateFlow(false)
    val restartOnNetworkChange: StateFlow<Boolean> = _restartOnNetworkChange.asStateFlow()

    private val _captchaStyleMod = MutableStateFlow(true)
    val captchaStyleMod: StateFlow<Boolean> = _captchaStyleMod.asStateFlow()

    private val _captchaForceTint = MutableStateFlow(true)
    val captchaForceTint: StateFlow<Boolean> = _captchaForceTint.asStateFlow()

    val privacyMode: StateFlow<Boolean> = prefs.privacyModeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    val profilesGestureEnabled: StateFlow<Boolean> = prefs.profilesGestureEnabledFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = true
    )

    private val _appLanguage = MutableStateFlow("system")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    // Blank = DEFAULT_PING_URL (see checkProxyPing()).
    private val _pingUrl = MutableStateFlow("")
    val pingUrl: StateFlow<String> = _pingUrl.asStateFlow()

    // "auto" (default, tries them all in order), "ipwhois", "ipsb", "ipapico", "ipinfo", or
    // "cloudflare" - see checkExitCountry()/resolveCountryViaMethod().
    private val _countryDetectionMethod = MutableStateFlow("auto")
    val countryDetectionMethod: StateFlow<String> = _countryDetectionMethod.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    private val _xrayConfig = MutableStateFlow(XrayConfig())
    val xrayConfig: StateFlow<XrayConfig> = _xrayConfig.asStateFlow()

    private val _vpnSettings = MutableStateFlow(VpnSettings())
    val vpnSettings: StateFlow<VpnSettings> = _vpnSettings.asStateFlow()

    private val _xraySettings = MutableStateFlow(XraySettings())
    val xraySettings: StateFlow<XraySettings> = _xraySettings.asStateFlow()

    private val _vlessConfig = MutableStateFlow(VlessConfig())
    val vlessConfig: StateFlow<VlessConfig> = _vlessConfig.asStateFlow()

    private val _vlessLinkHistory = MutableStateFlow<List<String>>(emptyList())
    val vlessLinkHistory: StateFlow<List<String>> = _vlessLinkHistory.asStateFlow()

    private val _autoLaunchSettings = MutableStateFlow(AutoLaunchSettings())
    val autoLaunchSettings: StateFlow<AutoLaunchSettings> = _autoLaunchSettings.asStateFlow()

    val profiles: StateFlow<List<Profile>> = profileManager.profiles
    val probeHistory = combine(historyStore.state, prefs.defaultFragmentationFlow, profiles) { history, defaults, current ->
        com.wireturn.app.domain.ProbeHistoryEnvironment.fragmentation = com.google.gson.Gson().toJson(defaults)
        // Change the projected value so Compose sorts/filters also react to new settings.
        val byId = current.associateBy { it.id }
        val digests = mutableMapOf<String, String>()
        history.map { result ->
            val profile = byId[result.profileId]
            val digest = profile?.let { digests.getOrPut(it.id) { com.wireturn.app.domain.probeConfigDigest(it) } }
            if (digest != null && result.configDigest != digest) result.copy(configDigest = "stale:" + result.configDigest) else result
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, historyStore.state.value)
    val favoriteProfiles = prefs.favoriteProfilesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val profileSort = prefs.profileSortFlow.stateIn(viewModelScope, SharingStarted.Eagerly, com.wireturn.app.domain.ProfileSortOrder.ORIGINAL)
    fun toggleFavoriteProfile(profile: Profile) { viewModelScope.launch { prefs.toggleFavoriteProfile(profile) } }
    fun setProfileSort(order: com.wireturn.app.domain.ProfileSortOrder) { viewModelScope.launch { prefs.setProfileSort(order) } }
    val currentProfileId: StateFlow<String> = profileManager.currentProfileId
    val subscriptions: StateFlow<List<com.wireturn.app.data.Subscription>> = profileManager.subscriptions
    val updatingSubIds: StateFlow<Set<String>> = profileManager.updatingSubIds
    val profileCountries: StateFlow<Map<String, String>> = profileManager.profileCountries

    val isArchitectureSupported: Boolean = Build.SUPPORTED_ABIS.any {
        it == "arm64-v8a" || it == "x86_64" || it == "armeabi-v7a" || it == "x86"
    }
    val deviceArchitecture: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private val _proxyPing = MutableStateFlow<PingResult?>(null)
    val proxyPing: StateFlow<PingResult?> = _proxyPing.asStateFlow()

    private val _proxyTransfer = MutableStateFlow<TransferResult?>(null)
    val proxyTransfer: StateFlow<TransferResult?> = _proxyTransfer.asStateFlow()

    private val _isHomeScreenActive = MutableStateFlow(false)

    private var pingJob: Job? = null
    private var countryJob: Job? = null
    private var exitJob: Job? = null
    val exitObservation = com.wireturn.app.domain.ExitObservationSession.state
    private var metricsJob: Job? = null

    sealed class PingResult {
        object Loading : PingResult()
        data class Success(val ms: Long) : PingResult()
        object Error : PingResult()
    }

    data class TransferResult(
        val rx: Long, 
        val tx: Long, 
        val rxSpeed: Long = 0, 
        val txSpeed: Long = 0
    )

    // Read hev-socks5-tunnel's own traffic counters whenever Android VPN mode is active.
    // native state is process-global, so any instance works regardless of which service created it.
    private val hevTunnel by lazy { HevSocks5Tunnel() }
    private val trafficSampler = com.wireturn.app.domain.TrafficSampler(
        clock = { android.os.SystemClock.elapsedRealtime() },
        hev = { withContext(Dispatchers.IO) {
            if (hevTunnel.TProxyIsRunning()) hevTunnel.TProxyGetStats() else null
        } },
        xray = { readXrayCounters(it) },
    )


    init {
        viewModelScope.launch {
            profiles.collect { profileUrlTests.profilesChanged(it) }
        }
        viewModelScope.launch {
            prefs.migrateRoutingOwnership()
            clearUnavailableActiveGroup()
            _onboardingDone.value = prefs.onboardingDoneFlow.first()
            _themeMode.value = prefs.themeModeFlow.first()
            _interfaceStyle.value = prefs.interfaceStyleFlow.first()
            _dynamicTheme.value = prefs.dynamicThemeFlow.first()
            _clientConfig.value = prefs.clientConfigFlow.first()
            _xrayConfig.value = prefs.xrayConfigFlow.first()
            _wgConfig.value = prefs.wgConfigFlow.first()
            _vlessConfig.value = prefs.vlessConfigFlow.first()
            _xraySettings.value = prefs.xraySettingsFlow.first()
            _vpnSettings.value = prefs.vpnSettingsFlow.first()
            _batteryNotificationDismissed.value = prefs.batteryNotificationDismissedFlow.first()
            _appsExclusionHintShown.value = prefs.appsExclusionHintShownFlow.first()
            _allowUnstableUpdates.value = prefs.allowUnstableUpdatesFlow.first()
            _waitForNetwork.value = prefs.waitForNetworkFlow.first()
            _restartOnNetworkChange.value = prefs.restartOnNetworkChangeFlow.first()
            _captchaStyleMod.value = prefs.captchaStyleModFlow.first()
            _captchaForceTint.value = prefs.captchaForceTintFlow.first()
            _appLanguage.value = prefs.appLanguageFlow.first()
            _vlessLinkHistory.value = prefs.vlessLinkHistoryFlow.first()
            _autoLaunchSettings.value = prefs.autoLaunchSettingsFlow.first()

            updateAutoLaunchJob(_autoLaunchSettings.value)
            applyLanguage(_appLanguage.value)

            _isInitialized.value = true

            // Migration: if ACTIVE keys are empty but we have profiles, activate current
            val currentProfiles = prefs.profilesFlow.first()
            if (currentProfiles.isNotEmpty()) {
                if (!prefs.hasActiveProfile()) {
                    val currentId = currentProfileId.value
                    val toActivate = currentProfiles.find { it.id == currentId } ?: currentProfiles.first()
                    prefs.saveFullProfile(toActivate.id, toActivate)
                }
            }

            launch { prefs.onboardingDoneFlow.collect { _onboardingDone.value = it } }
            launch { prefs.themeModeFlow.collect { _themeMode.value = it } }
            launch { prefs.interfaceStyleFlow.collect { _interfaceStyle.value = it } }
            launch { prefs.vpnSettingsFlow.collect { _vpnSettings.value = it } }
            launch { prefs.dynamicThemeFlow.collect { _dynamicTheme.value = it } }
            launch { prefs.clientConfigFlow.collect { _clientConfig.value = it } }
            launch { prefs.xrayConfigFlow.collect { _xrayConfig.value = it } }
            launch { prefs.wgConfigFlow.collect { _wgConfig.value = it } }
            launch { prefs.vlessConfigFlow.collect { _vlessConfig.value = it } }
            launch { prefs.xraySettingsFlow.collect { _xraySettings.value = it } }
            launch { prefs.batteryNotificationDismissedFlow.collect { _batteryNotificationDismissed.value = it } }
            launch { prefs.appsExclusionHintShownFlow.collect { _appsExclusionHintShown.value = it } }
            launch { prefs.allowUnstableUpdatesFlow.collect { _allowUnstableUpdates.value = it } }
            launch { prefs.waitForNetworkFlow.collect { _waitForNetwork.value = it } }
            launch { prefs.restartOnNetworkChangeFlow.collect { _restartOnNetworkChange.value = it } }
            launch { prefs.captchaStyleModFlow.collect { _captchaStyleMod.value = it } }
            launch { prefs.captchaForceTintFlow.collect { _captchaForceTint.value = it } }
            launch { prefs.appLanguageFlow.collect { _appLanguage.value = it } }
            launch { prefs.pingUrlFlow.collect { _pingUrl.value = it } }
            launch { prefs.countryDetectionMethodFlow.collect { _countryDetectionMethod.value = it } }
            launch { prefs.autoLaunchSettingsFlow.collect { _autoLaunchSettings.value = it; updateAutoLaunchJob(it) } }
            launch { prefs.vlessLinkHistoryFlow.collect { _vlessLinkHistory.value = it } }

            launch {
                var isFirstEmission = true
                allowUnstableUpdates
                    .debounce(2_000.milliseconds)
                    .collect { 
                        appUpdater.checkForUpdate(silent = true, allowUnstable = it, force = !isFirstEmission)
                        isFirstEmission = false
                    }
            }

        }

        viewModelScope.launch { coreManager.observeCoreLifecycle() }
        viewModelScope.launch { coreManager.observeCoreServiceStatus() }
        viewModelScope.launch { coreManager.observeCaptchaEvents() }
        viewModelScope.launch {
            delay(1_500.milliseconds)
            // Enable polling while either service is active. Traffic uses TUN in VPN mode
            // and Xray IPC in proxy-only mode; the source is selected on every tick.
            com.wireturn.app.domain.homeDiagnosticStates(_isHomeScreenActive, combine(XrayServiceState.state, VpnServiceState.state) { xrayState, vpnState ->
                xrayState == XrayState.Running || xrayState == XrayState.DirectRoute || vpnState == VpnState.Running
            }).collect { active ->
                if (active == true) {
                    checkProxyPing(delayFirst = true)
                    startMetricsPoller()
                } else {
                    pingJob?.cancel()
                    stopMetricsPoller()
                }
            }
        }
        viewModelScope.launch {
            com.wireturn.app.domain.exitDiagnosticSessions(_isHomeScreenActive, combine(coreState, CoreServiceState.session) { state, session ->
                session?.generation?.takeIf { state is CoreState.Connected || state is CoreState.Suppressed }
            }).collect { key ->
                if (com.wireturn.app.domain.ExitObservationSession.needsRefresh(key)) refreshExitObservation()
            }
        }
        viewModelScope.launch {
            com.wireturn.app.domain.homeDiagnosticStates(_isHomeScreenActive,
                coreState.map { it is CoreState.Connected || it is CoreState.Suppressed }).collect { active ->
                    if (active == true) checkExitCountry() else countryJob?.cancel()
                }
        }
        coreManager.syncInitialState()
    }

    fun setHomeScreenActive(active: Boolean) { 
        _isHomeScreenActive.value = active 
    }

    private fun startMetricsPoller() {
        metricsJob?.cancel()
        metricsJob = viewModelScope.launch {
            com.wireturn.app.domain.pollWhileVisible(_isHomeScreenActive) {
                    val xrayState = XrayServiceState.state.value
                    val sample = trafficSampler.poll(
                        vpnRunning = VpnServiceState.state.value == VpnState.Running,
                        xrayPresent = xrayState != XrayState.Idle,
                        extended = XrayServiceState.session.value?.settings?.networkRouter == com.wireturn.app.data.NetworkRouter.SING_BOX_EXTENDED,
                        socket = XrayServiceState.statsSocketName.value,
                        session = "${CoreServiceState.session.value?.generation}:${VpnServiceState.generation}",
                    )
                    _proxyTransfer.value = sample?.let { TransferResult(it.rx, it.tx, it.rxSpeed, it.txSpeed) }
            }
        }
    }

    private fun stopMetricsPoller() {
        metricsJob?.cancel()
        metricsJob = null
        _proxyTransfer.value = null
        trafficSampler.reset()
    }

    private suspend fun readXrayCounters(socketName: String): String? = withContext(Dispatchers.IO) {
            LocalSocket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                socket.outputStream.write("stats\n".toByteArray())
                socket.outputStream.flush()
                socket.inputStream.bufferedReader().readLine()
            }
    }

    override fun onCleared() { 
        coreManager.destroy()
    }

    fun setDynamicTheme(enabled: Boolean) { 
        viewModelScope.launch { prefs.setDynamicTheme(enabled) } 
    }
    
    fun setThemeMode(mode: ThemeMode) { 
        viewModelScope.launch { prefs.setThemeMode(mode) } 
    }

    fun setPrivacyMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setPrivacyMode(enabled) }
    }

    fun setProfilesGestureEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setProfilesGestureEnabled(enabled) }
    }

    fun updateAutoLaunchSettings(settings: AutoLaunchSettings) {
        viewModelScope.launch { 
            prefs.updateAutoLaunchSettings(settings)
            CoreTileService.requestUpdate(getApplication())
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun isUrlReachable(url: String): Boolean {
        if (!isNetworkAvailable()) return true
        repeat(3) {
            try {
                val code = withContext(Dispatchers.IO) {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    try {
                        conn.connectTimeout = 2500
                        conn.readTimeout = 2500
                        conn.requestMethod = "HEAD"
                        conn.useCaches = false
                        conn.responseCode
                    } finally { conn.disconnect() }
                }
                if (code in 200..399) return true
            } catch (_: Exception) {}
            if (it < 2) delay(300.milliseconds)
        }
        return false
    }

    private fun updateAutoLaunchJob(settings: AutoLaunchSettings) {
        autoLaunchJob?.cancel()
        if (settings.enabled) {
            autoLaunchJob = ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    val isReachable = isUrlReachable(settings.checkUrl)
                    val isRunning = CoreServiceState.isRunning.value
                    if (!isReachable && !isRunning) {
                        withContext(Dispatchers.Main) { startCore() }
                    } else if (isReachable && isRunning) {
                        withContext(Dispatchers.Main) { 
                            CoreService.stop(getApplication(), byUser = false)
                        }
                    }
                    delay((settings.intervalMinutes * 60 * 1_000L).milliseconds)
                }
            }
        }
    }

    fun startCore() {
        ProcessLifecycleOwner.get().lifecycleScope.launch { startCoreInternal() }
    }
    
    private suspend fun startCoreInternal(forceRestart: Boolean = false) {
        clearUnavailableActiveGroup()
        if (!forceRestart) {
            val groupId = prefs.activeConnectionGroupFlow.first()
            val group = prefs.connectionGroupsFlow.first().find { it.id == groupId }
            if (group != null && com.wireturn.app.domain.ManualGroupPolicy.allows(group)) {
                groupRunner.connect(group)
                return
            }
        }
        // The screen collector may not have observed the just-committed selection yet.
        coreManager.startCore(prefs.clientConfigFlow.first(), forceRestart)
    }

    private suspend fun clearUnavailableActiveGroup() {
        val id = prefs.activeConnectionGroupFlow.first()
        if (com.wireturn.app.domain.ManualGroupPolicy.activeGroupToClear(id, prefs.connectionGroupsFlow.first()) == null) return
        // Stop only this group's coordinator; the selected profile and running VPN stay intact.
        if (groupRunner.state.value.groupId == id) groupRunner.release()
        if (prefs.activeConnectionGroupFlow.first() == id) prefs.setActiveConnectionGroup(null)
    }

    fun stopCore() {
        // Mark probes cancelled before stopping sockets; teardown errors must not replace history.
        profileUrlTestJob?.cancel()
        groupRunner.release()
        coreManager.stopCore()
    }

    fun dismissCaptcha() { coreManager.dismissCaptcha() }
    fun submitCaptchaResult(sessionId: Long, token: String) { CoreServiceState.submitCaptchaResult(sessionId, token) }
    fun clearLogs() { AppLogsState.clearLogs() }
    
    fun saveClientConfig(config: ClientConfig) {
        viewModelScope.launch {
            prefs.saveClientConfig(config)
            val profile = profiles.value.find { it.id == currentProfileId.value } ?: return@launch
            val updatedProfile = profile.copy(kernelConfig = config.kernelConfig)
            prefs.saveActiveProfilePart(updatedProfile)
            updateCurrentProfileInList()
        }
    }
    
    fun removeVlessLinkFromHistory(link: String) { 
        viewModelScope.launch { prefs.removeVlessLinkFromHistory(link) } 
    }
    
    fun setOnboardingDone() { 
        viewModelScope.launch { prefs.setOnboardingDone(true) } 
    }
    
    fun setBatteryNotificationDismissed(v: Boolean) { 
        viewModelScope.launch { prefs.setBatteryNotificationDismissed(v) } 
    }
    
    fun setAppsExclusionHintShown(v: Boolean) { 
        viewModelScope.launch { prefs.setAppsExclusionHintShown(v) } 
    }
    
    fun setAllowUnstableUpdates(v: Boolean) { 
        viewModelScope.launch { prefs.setAllowUnstableUpdates(v) } 
    }
    
    fun setWaitForNetwork(v: Boolean) { 
        viewModelScope.launch { prefs.setWaitForNetwork(v) } 
    }
    
    fun setRestartOnNetworkChange(v: Boolean) { 
        viewModelScope.launch { prefs.setRestartOnNetworkChange(v) } 
    }
    
    fun setVpnEnabled(v: Boolean) { 
        viewModelScope.launch { prefs.setVpnEnabled(v) } 
    }

    fun setCaptchaStyleMod(v: Boolean) {
        viewModelScope.launch { prefs.setCaptchaStyleMod(v) } 
    }
    
    fun setCaptchaForceTint(v: Boolean) { 
        viewModelScope.launch { prefs.setCaptchaForceTint(v) } 
    }
    
    fun setAppLanguage(l: String) {
        _appLanguage.value = l
        viewModelScope.launch {
            prefs.setAppLanguage(l)
            applyLanguage(l)
        }
    }

    fun setPingUrl(url: String) {
        viewModelScope.launch { prefs.setPingUrl(url) }
    }

    fun setCountryDetectionMethod(method: String) {
        viewModelScope.launch { prefs.setCountryDetectionMethod(method) }
    }

    private fun applyLanguage(lang: String) {
        val loc = if (lang == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang)
        AppCompatDelegate.setApplicationLocales(loc)
    }

    fun checkProxyPing(delayFirst: Boolean = false) {
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            _proxyPing.value = PingResult.Loading
            var sawProxy = false
            val target = _pingUrl.value.ifBlank { DEFAULT_PING_URL }
            repeat(10) { attempt ->
                // Re-resolved every attempt: works for Xray or VPN-mode-without-Xray alike, and
                // naturally follows the active source if it changes mid-sequence (Xray priority).
                // NO_PROXY isn't treated as final here - Xray reports non-Idle (and so counts as the
                // active source) from Starting onward, before its local socks listener is actually up,
                // so a NO_PROXY blip right as it's coming up should retry like any other failed attempt
                // rather than permanently parking the ping indicator on "unknown".
                val proxy = activeLocalSocksProxy()
                if (proxy != Proxy.NO_PROXY) {
                    sawProxy = true
                    if (delayFirst && attempt == 0) delay(1_000.milliseconds)
                    val res = withContext(Dispatchers.IO) {
                        try {
                            val conn = java.net.URL(target).openConnection(proxy) as java.net.HttpURLConnection
                            try {
                                conn.connectTimeout = 3000
                                conn.readTimeout = 3000
                                conn.instanceFollowRedirects = false
                                val elapsed = measureTimeMillis { conn.responseCode }
                                PingResult.Success(elapsed)
                            } finally { conn.disconnect() }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (res != null) {
                        _proxyPing.value = res
                        return@launch
                    }
                }
                delay(1_000.milliseconds)
            }
            _proxyPing.value = if (sawProxy) PingResult.Error else null
        }
    }

    fun checkExitCountry() {
        countryJob?.cancel()
        val targetProfileId = currentProfileId.value
        countryJob = viewModelScope.launch {
            repeat(2) { pass ->
                if (pass > 0) delay(10_000.milliseconds)
                resolveExitCountryOnce(targetProfileId)
            }
        }
    }

    private suspend fun resolveExitCountryOnce(profileId: String) {
        repeat(10) { attempt ->
            val proxy = activeLocalSocksProxy()
            if (proxy != Proxy.NO_PROXY) {
                if (attempt == 0) delay(1_000.milliseconds)
                val method = _countryDetectionMethod.value
                // "auto" tries every real method in order until one succeeds, same fallback-chain
                // idea Hiddify's ProxyRepositoryImpl uses (ipwho.is -> ip.sb -> ipapi.co -> ipinfo.io)
                // - useful since any single one of these can be blocked/down in a given region.
                val methodsToTry = if (method == "auto") COUNTRY_DETECTION_AUTO_ORDER else listOf(method)
                val country = withContext(Dispatchers.IO) {
                    methodsToTry.firstNotNullOfOrNull { m ->
                        currentCoroutineContext().ensureActive()
                        try { resolveCountryViaMethod(m, proxy) } catch (_: Exception) { null }
                    }
                }
                if (country != null) {
                    profileManager.saveProfileCountry(profileId, country)
                    return
                }
            }
            delay(1_000.milliseconds)
        }
    }

    // All of these geolocate the *connecting* IP (i.e. the exit, once dialed through `proxy`) -
    // none take an explicit IP argument, so none need a prior "what's my IP" round trip.
    private suspend fun resolveCountryViaMethod(method: String, proxy: Proxy): String? = when (method) {
        // Plain-text body, just the ISO-3166 code (e.g. "US\n") - no JSON parsing needed beyond
        // trimming and a sanity check on shape.
        "ipinfo" -> {
            val body = httpGetThroughProxy("https://ipinfo.io/country", proxy).trim()
            body.takeIf { Regex("^[A-Z]{2}$").matches(it) }
        }
        // ipwho.is / api.ip.sb / ipapi.co all happen to share the same top-level JSON field name.
        "ipwhois" -> COUNTRY_CODE_JSON_REGEX.find(httpGetThroughProxy("https://ipwho.is/", proxy))?.groupValues?.get(1)
        "ipsb" -> COUNTRY_CODE_JSON_REGEX.find(httpGetThroughProxy("https://api.ip.sb/geoip/", proxy))?.groupValues?.get(1)
        "ipapico" -> COUNTRY_CODE_JSON_REGEX.find(httpGetThroughProxy("https://ipapi.co/json/", proxy))?.groupValues?.get(1)
        // Cloudflare's trace endpoint echoes the connecting IP's geolocation back as a
        // "loc=XX" (ISO-3166) line in the body - the fallback for "cloudflare" and anything
        // unrecognized.
        else -> {
            val body = httpGetThroughProxy("https://1.1.1.1/cdn-cgi/trace", proxy)
            Regex("(?m)^loc=([A-Z]{2})$").find(body)?.groupValues?.get(1)
        }
    }

    private suspend fun httpGetThroughProxy(url: String, proxy: Proxy): String {
        return com.wireturn.app.domain.ProxyHttpText.fetch(url, proxy)
    }

    fun checkForUpdate() {
        viewModelScope.launch { 
            appUpdater.checkForUpdate(silent = false, allowUnstable = _allowUnstableUpdates.value) 
        } 
    }
    
    fun downloadUpdate() { 
        viewModelScope.launch { appUpdater.downloadUpdate() } 
    }
    
    fun installUpdate() { appUpdater.installUpdate() }

    fun downloadGeoAssets(variant: com.wireturn.app.domain.GeoResourceSet) {
        viewModelScope.launch {
            geoAssetsManager.download(variant)
            if (geoAssetsManager.state.value is GeoAssetsState.Success) {
                updateXraySettings(xraySettings.value.copy(geoVariant = variant.id))
            }
        }
    }

    fun cancelGeoAssetsDownload() = geoAssetsManager.cancelDownload()

    fun deleteGeoAssets() {
        viewModelScope.launch { geoAssetsManager.delete() }
    }

    fun geoAssetsInstalledAt(): Long = geoAssetsManager.installedUpdatedAt()
    fun geoAssetsInstalled(): Boolean = geoAssetsManager.installedFilesExist()

    fun updateWgConfig(c: WgConfig) {
        _wgConfig.value = c
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveWgConfig(c)
            updateCurrentProfileInList()
        }
    }

    fun updateXraySettings(s: XraySettings) {
        _xraySettings.value = s
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveXraySettings(s)
            updateCurrentProfileInList()
        }
    }

    fun refreshExitObservation() {
        val session = CoreServiceState.session.value ?: return
        if (coreState.value !is CoreState.Connected && coreState.value !is CoreState.Suppressed) return
        exitJob?.cancel()
        val requestToken = com.wireturn.app.domain.ExitObservationSession.begin(session.generation)
        exitJob = viewModelScope.launch {
            try {
            repeat(3) { attempt ->
                delay(if (attempt == 0) 1000 else 3000)
                val result = try {
                    withContext(Dispatchers.IO) { com.wireturn.app.domain.ExitObservation.fetch(activeLocalSocksProxy()) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
                if (CoreServiceState.session.value?.generation != session.generation) return@launch
                if (result != null) {
                    com.wireturn.app.domain.ExitObservationSession.complete(session.generation, result, requestToken)
                    return@launch
                }
            }
            com.wireturn.app.domain.ExitObservationSession.complete(session.generation, null, requestToken)
            } finally { com.wireturn.app.domain.ExitObservationSession.cancel(session.generation, requestToken) }
        }
    }

    suspend fun saveValidatedXraySettings(s: XraySettings) {
        com.wireturn.app.kernel.RoutingConfigCheck.settings(getApplication(), s)
        // Do not expose unvalidated settings to collectors or a running service.
        prefs.saveXraySettings(s)
        _xraySettings.value = s
        updateCurrentProfileInList()
    }

    fun saveVpnSettings(s: VpnSettings) {
        viewModelScope.launch { prefs.saveVpnSettings(s) }
    }

    fun toggleAppExclusion(p: String) {
        val cur = _vpnSettings.value.excludedApps
        viewModelScope.launch {
            prefs.saveExcludedApps(if (cur.contains(p)) cur - p else cur + p)
        }
    }

    fun saveExcludedApps(s: Set<String>) {
        viewModelScope.launch { prefs.saveExcludedApps(s) }
    }

    fun updateXrayConfig(c: XrayConfig) {
        _xrayConfig.value = c.copy(routingEnabled = _xrayConfig.value.routingEnabled)
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveXrayConfig(c)
            updateCurrentProfileInList()
        }
    }

    fun enableRouting() {
        viewModelScope.launch { prefs.enableRouting() }
    }

    fun disableRouting(consent: com.wireturn.app.data.RoutingDisableConsent) {
        viewModelScope.launch { prefs.disableRouting(consent) }
    }

    fun updateVlessConfig(c: VlessConfig) {
        _vlessConfig.value = c
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveVlessConfig(c)
            updateCurrentProfileInList()
        }
    }

    private fun updateCurrentProfileInList() {
        val curId = currentProfileId.value
        val profile = profiles.value.find { it.id == curId } ?: return
        val withKernel = profile.copy(kernelConfig = _clientConfig.value.kernelConfig)
        profileManager.updateCurrentProfile(withKernel.copy(
            xrayProtocol = _xrayConfig.value.protocol,
            xrayEnabled = _xrayConfig.value.enabled,
            wgConfig = _wgConfig.value,
            vlessConfig = _vlessConfig.value
        ))
    }

    fun selectProfileAndRestart(id: String, profile: Profile? = null, onCompletion: (() -> Unit)? = null) {
        val target = profile ?: profiles.value.find { it.id == id } ?: return
        val wasAlreadySelected = id == currentProfileId.value

        viewModelScope.launch {
            // A subscription may commit after the row is tapped. Activate its current
            // stored endpoint, and do not change the running group for a removed row.
            if (prefs.activateSelectedProfile(target) == null) {
                onCompletion?.invoke()
                return@launch
            }
            groupRunner.release()
            if (CoreServiceState.isRunning.value && (!wasAlreadySelected || profile != null)) {
                startCoreInternal(forceRestart = true)
            }
            onCompletion?.invoke()
        }
    }

    fun addFullProfile(
        name: String,
        clientConfig: ClientConfig,
        xrayConfig: XrayConfig,
        wgConfig: WgConfig,
        vlessConfig: VlessConfig,
        onSaved: () -> Unit = {},
    ) {
        val id = java.util.UUID.randomUUID().toString()
        val defaultName = getApplication<Application>().getString(R.string.profile_default_name)
        val finalName = if (name.isBlank() || name == defaultName) profileManager.nextDefaultProfileName() else name
        val newProfile = Profile(
            id = id,
            name = finalName,
            kernelConfig = clientConfig.kernelConfig,
            xrayProtocol = xrayConfig.protocol,
            xrayEnabled = xrayConfig.enabled,
            wgConfig = wgConfig,
            vlessConfig = vlessConfig
        ).sanitize(defaultName)
        val wasEmpty = profiles.value.isEmpty()
        viewModelScope.launch {
            prefs.editProfiles(com.wireturn.app.data.ProfileListEdit.Append(newProfile))
            if (wasEmpty) {
                selectProfileAndRestart(id, newProfile)
            }
            onSaved()
        }
    }

    fun updateProfileById(profileId: String, update: (Profile) -> Profile) {
        val profile = profiles.value.find { it.id == profileId } ?: return
        profileManager.updateCurrentProfile(update(profile))
    }

    fun cloneProfile(id: String, name: String) = profileManager.cloneProfile(id, name)
    fun deleteProfile(id: String) = deleteProfiles(listOf(id))
    fun deleteProfiles(ids: List<String>) {
        // Not (profiles.size - ids.size) <= 0: if ids contains a stale id no longer in profiles
        // (e.g. a selection made before a background subscription update changed the list), that
        // arithmetic overcounts and wrongly declares "will be empty" while profiles actually remain -
        // clearActiveProfile() would then wipe the active selection for no reason.
        val willBeEmpty = profiles.value.all { it.id in ids }
        if (willBeEmpty) {
            val wasRunning = CoreServiceState.isRunning.value
            if (wasRunning) stopCore()
            viewModelScope.launch {
                // Give CoreService's stop intent a moment to register (sets userStopped=true) before
                // clearing the active config - otherwise clearing it first can race the hot-reload
                // watcher into reporting a validation error instead of a clean stop.
                if (wasRunning) delay(150.milliseconds)
                prefs.clearActiveProfile()
            }
        }
        profileManager.deleteProfiles(ids) { p -> selectProfileAndRestart(p.id, p) }
    }
    fun renameProfile(id: String, name: String) = profileManager.renameProfile(id, name)
    fun nextDefaultProfileName() = profileManager.nextDefaultProfileName()

    fun reorderProfiles(list: List<Profile>) = profileManager.reorderProfiles(list)
    fun getProfilesJson(ids: List<String>) = profileManager.getProfilesJson(ids)
    fun exportProfilesToZip(ids: List<String>) = profileManager.exportProfilesToZip(ids)
    suspend fun importProfilesFromZip(s: java.io.InputStream) = profileManager.importProfilesFromZip(s)
    suspend fun importProfilesFromZipWithResult(s: java.io.InputStream) = profileManager.importProfilesFromZipWithResult(s)
    suspend fun importProfiles(data: List<Pair<String?, String>>) = profileManager.importProfiles(data).first

    /** Like [importProfiles], but also returns the freshly-imported profiles (with their newly
     * assigned local ids) so a caller can scroll/highlight to what was just added. */
    suspend fun importProfilesWithResult(data: List<Pair<String?, String>>): Pair<com.wireturn.app.domain.ImportResult, List<Profile>> {
        val (result, imported, _) = profileManager.importProfiles(data)
        return result to imported
    }

    suspend fun smartImport(text: String): com.wireturn.app.domain.ImportStatus {
        val trimmed = text.trim()
        if (com.wireturn.app.domain.TunaRoutingLink.recognizes(trimmed)) {
            try {
                prefs.importTunaRouting(com.wireturn.app.domain.TunaRoutingLink.parse(trimmed))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return com.wireturn.app.domain.ImportStatus.InvalidFormat
            }
            return com.wireturn.app.domain.ImportStatus.Success(message =
                "Общая маршрутизация импортирована. Личные правила сохранены. Для активного VPN переподключитесь; правила подписки могут иметь приоритет.")
        }
        if (com.wireturn.app.domain.AppExclusionsLink.recognizes(trimmed)) {
            val command = runCatching { com.wireturn.app.domain.AppExclusionsLink.command(trimmed) }
                .getOrNull() ?: return com.wireturn.app.domain.ImportStatus.InvalidFormat
            try {
                prefs.importAppExclusions(command)
            } catch (e: IllegalArgumentException) {
                return com.wireturn.app.domain.ImportStatus.InvalidFormat
            }
            return com.wireturn.app.domain.ImportStatus.Success(message =
                "Режим «${if (command.bypass) "Без VPN" else "Через VPN"}» включён; список дополнен (${command.packages.size}). Для активного VPN переподключитесь.")
        }

        com.wireturn.app.domain.importWgIniDocument(trimmed) { document ->
            val (result, imported, _) = profileManager.importProfiles(listOf(null to document), onAutoSelect = {})
            result to imported
        }?.let { return it }


        // 1. Subscription
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            return profileManager.fetchSubscription(trimmed)
        }

        // 2. Encoded Profile (or, if the decoded payload is itself a bare URL, a subscription
        // wrapped in a wireturn:// container - the container is scheme-agnostic, see ProfileEncoder)
        if (trimmed.startsWith("tuna://") || trimmed.startsWith("wireturn://") || trimmed.startsWith("wt://")) {
            val encoded = trimmed.substringAfter("://")
            val json = withContext(Dispatchers.Default) { com.wireturn.app.domain.ProfileEncoder.decode(encoded) }
            if (json != null) {
                val decodedTrimmed = json.trim()
                if (decodedTrimmed.startsWith("https://") || decodedTrimmed.startsWith("http://")) {
                    return profileManager.fetchSubscription(decodedTrimmed)
                }
                val (result, imported) = importProfilesWithResult(listOf(null to json))
                if (result.total == 0) return com.wireturn.app.domain.ImportStatus.InvalidFormat
                val targetId = if (result.added > 0 || result.updated > 0) imported.firstOrNull()?.id else null
                return com.wireturn.app.domain.ImportStatus.Success(targetId, result)
            }
            return com.wireturn.app.domain.ImportStatus.InvalidFormat
        }

        // 3. Raw JSON Profile (standalone or array)
        try {
            val element = com.google.gson.JsonParser.parseString(trimmed)
            if (element.isJsonObject || element.isJsonArray) {
                val (result, imported) = importProfilesWithResult(listOf(null to trimmed))
                if (result.total > 0) {
                    return com.wireturn.app.domain.ImportStatus.Success(imported.firstOrNull()?.id, result)
                }
            }
        } catch (_: Exception) {
        }

        // 4. Kernel Configs (raw URIs)
        val masterdns = com.wireturn.app.data.kernel.MasterDnsConfig.parse(trimmed)
        if (masterdns != null) return com.wireturn.app.domain.ImportStatus.KernelConfigDetected("MasterDnsVPN", com.google.gson.Gson().toJson(masterdns), trimmed)
        val vpn = com.wireturn.app.data.kernel.VpnConfig.parse(trimmed)?.let { com.wireturn.app.domain.VlessFingerprintImport.vpn(it) }
        if (vpn != null) return com.wireturn.app.domain.ImportStatus.KernelConfigDetected("VPN", com.google.gson.Gson().toJson(vpn), trimmed)
        val qwdtt = QwdttConfig.parse(trimmed)
        if (qwdtt != null) return com.wireturn.app.domain.ImportStatus.KernelConfigDetected(
            "qWDTT",
            com.google.gson.Gson().toJson(qwdtt),
            trimmed
        )

        val webdav = WebdavConfig.parse(trimmed)
        if (webdav != null) return com.wireturn.app.domain.ImportStatus.KernelConfigDetected("WebDAV", com.google.gson.Gson().toJson(webdav), trimmed)

        return com.wireturn.app.domain.ImportStatus.InvalidFormat
    }

    suspend fun importProfileFromLink(link: String): com.wireturn.app.domain.ImportStatus {
        return smartImport(link)
    }

    suspend fun refreshSubscription(url: String, allowDirectUpdate: Boolean = false): com.wireturn.app.domain.ImportStatus =
        profileManager.fetchSubscription(url, allowDirectUpdate = allowDirectUpdate)

    fun deleteSubscription(id: String) {
        val willBeEmpty = profiles.value.count { it.subscriptionId != id } <= 0
        if (willBeEmpty) {
            if (CoreServiceState.isRunning.value) stopCore()
            viewModelScope.launch { prefs.clearActiveProfile() }
        }
        profileManager.deleteSubscription(id) { selectProfileAndRestart(it.id, it) }
    }

    fun updateSubscription(sub: com.wireturn.app.data.Subscription) = profileManager.updateSubscription(sub)
    
    fun resetAllSettings(c: Context) {
        viewModelScope.launch {
            c.stopService(Intent(c, CoreService::class.java))
            c.stopService(Intent(c, XrayService::class.java))
            CoreServiceState.setStatus(CoreStatus.Idle)
            XrayServiceState.updateStatus(XrayState.Idle)
            prefs.resetAll()
            coreManager.clearState()
            AppLogsState.clearLogs()
            val intent = Intent(c, com.wireturn.app.ui.activities.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            c.startActivity(intent)
        }
    }

    companion object {
        private var autoLaunchJob: Job? = null
        const val DEFAULT_PING_URL = "https://www.google.com/generate_204"

        // Same order Hiddify's own ProxyRepositoryImpl tries its 4 shared sources in, plus our
        // own Cloudflare method appended at the end.
        private val COUNTRY_DETECTION_AUTO_ORDER =
            listOf("ipwhois", "ipsb", "ipapico", "ipinfo", "cloudflare")

        private val COUNTRY_CODE_JSON_REGEX = Regex("\"country_code\"\\s*:\\s*\"([A-Z]{2})\"")
    }
}
