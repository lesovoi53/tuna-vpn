package com.wireturn.app.viewmodel

import android.app.Application
import android.net.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Profile
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CfScreenState(
    val profile: Profile? = null, val originalHost: String = "", val selectedIp: String? = null,
    val choices: List<CfResult> = emptyList(), val results: List<CfResult> = emptyList(),
    val unavailable: CfUnavailable? = null, val busy: Boolean = false, val completed: Int = 0,
    val total: Int = 0, val phase: String = "idle", val message: String? = null, val wifi: Boolean = true,
    val tcpChecked: Int = 0, val profileChecked: Int = 0, val confirmed: Int = 0, val stopping: Boolean = false,
    val applying: Boolean = false, val networkAvailable: Boolean = false,
    val elapsedSeconds: Long = 0, val activeSettings: CfSettings? = null,
)

class CloudflareViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = AppPreferences(application)
    private val cm = application.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(CfScreenState())
    val state = mutable.asStateFlow()
    private var source: Profile? = null
    private var record: CfSavedAddresses? = null
    private var scan: Job? = null
    private var observer: Job? = null
    private var activeScan: CfScanToken? = null
    private var rangeRefresh: Job? = null
    private var applying = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = networkChanged()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = networkChanged()
    }
    init {
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
    }
    private fun physical(): Network? {
        fun valid(n: Network) = cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
        return cm.activeNetwork?.takeIf { valid(it) } ?: cm.allNetworks.filter { valid(it) }
            .sortedByDescending { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }.firstOrNull()
    }
    private fun stamp(network: Network?): String? = network?.let { n ->
        val p = cm.getLinkProperties(n)
        "${n.networkHandle}:${p?.interfaceName}:${p?.linkAddresses?.sortedBy { it.toString() }}:${p?.dnsServers}"
    }
    private fun networkChanged() { viewModelScope.launch {
        val network = physical()
        mutable.update { it.copy(networkAvailable = network != null,
            wifi = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) }
        if (activeScan != null && stamp(physical()) != activeScan?.networkStamp) {
            activeScan?.invalidate()
            scan?.cancel(); rangeRefresh?.cancel()
            mutable.update { it.copy(results = emptyList(), message = "network_changed") }
        }
    } }
    fun load(id: String) {
        if (observer != null) return
        networkChanged()
        observer = viewModelScope.launch {
            prefs.cloudflareProfileFlow(id)
                .collect { (current, saved) ->
                    if (activeScan != null && current?.let { CloudflareProfile.snapshot(it) } != activeScan?.profileSnapshot && !applying) {
                        activeScan?.invalidate()
                        scan?.cancel()
                        mutable.update { it.copy(results = emptyList(), message = "profile_changed") }
                    }
                    record = saved?.takeIf { current != null && it.accepts(current) }
                    source = record?.let { CloudflareProfile.restore(it.source) } ?: current
                    val plan = source?.let { runCatching { CloudflareProfile.plan(it) } }
                    mutable.update { s -> s.copy(profile = current, originalHost = source?.let { CloudflareProfile.originalAddress(it) }.orEmpty(),
                        selectedIp = current?.let { record?.effectiveIp(it) }, choices = record?.results.orEmpty(),
                        unavailable = (plan?.exceptionOrNull() as? CfProfileException)?.reason,
                        message = if (current == null) "missing" else s.message) }
                }
        }
    }
    fun start(settings: CfSettings, custom: String) {
        if (mutable.value.busy || applying) return
        val expected = mutable.value.profile ?: return
        val profile = source ?: return
        val plan = runCatching { CloudflareProfile.plan(profile) }.getOrElse { return }
        val network = physical() ?: run { mutable.update { it.copy(message = "no_network") }; return }
        val inputs = custom.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
        if (inputs.size > settings.count || inputs.any { CfAddress.literal(it) == null }) {
            mutable.update { it.copy(message = "bad_ips") }; return
        }
        val frozen = CloudflareProfile.snapshot(expected)
        val ticket = CfScanToken(frozen, stamp(network) ?: return)
        activeScan = ticket
        val previousResults = record?.results.orEmpty()
        val chosenIp = mutable.value.selectedIp
        fun currentRun() = activeScan === ticket && ticket.accepts(
            mutable.value.profile?.let { CloudflareProfile.snapshot(it) }, stamp(physical()))
        mutable.update { it.copy(busy = true, completed = 0, total = settings.count, results = emptyList(),
            tcpChecked = 0, profileChecked = 0, confirmed = 0, stopping = false,
            elapsedSeconds = 0, activeSettings = settings,
            phase = "scan", message = null, wifi = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) }
        scan = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            val clock = launch {
                while (isActive) {
                    mutable.update { it.copy(elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000) }
                    delay(1000)
                }
            }
            val scanner = CloudflareScanner(getApplication(), network)
            try {
                // Cached/bundled ranges are ready immediately. Refresh never blocks candidate discovery.
                val ranges = scanner.ranges(refresh = false)
                if (rangeRefresh?.isActive != true) rangeRefresh = viewModelScope.launch(Dispatchers.IO) {
                    scanner.ranges(refresh = true)
                }
                if (inputs.any { value -> val ip = CfAddress.literal(value)!!; ranges.none { it.contains(ip) } }) {
                    mutable.update { it.copy(message = "not_cf") }; return@launch
                }
                val ipv6 = cm.getLinkProperties(network)?.linkAddresses?.any {
                    it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress
                } == true
                if (!ipv6 && inputs.any { ':' in it }) { mutable.update { it.copy(message = "no_ipv6") }; return@launch }
                val candidates = CfAddress.candidates(settings.count, ranges,
                    inputs + listOfNotNull(chosenIp) + previousResults.map { it.ip } + listOf(plan.originalHost), ipv6)
                mutable.update { it.copy(total = candidates.size, phase = "scan") }
                var savedResults = previousResults
                val outcome = scanner.scan(plan, candidates, settings, onResult = { result ->
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (!currentRun()) return@withContext
                        val measured = CfResultCache.visible(mutable.value.results, result)
                        val usable = CfResultCache.merge(savedResults, listOf(result))
                        if (usable != savedResults) {
                            // The DataStore transaction also verifies the actual profile, not just the UI snapshot.
                            prefs.saveCloudflareAddresses(expected, CfSavedAddresses(plan.snapshot, usable, chosenIp), false)
                            savedResults = usable
                        }
                        if (currentRun()) mutable.update { it.copy(results = measured, choices = usable) }
                    }
                }, onProgress = { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        if (currentRun()) mutable.update { it.copy(completed = progress.completed,
                            tcpChecked = progress.tcpChecked, profileChecked = progress.profileChecked, confirmed = progress.confirmed,
                            phase = if (progress.stability) "stability" else "scan") }
                    }
                })
                ensureActive()
                if (!currentRun()) return@launch
                val usable = mutable.value.results.filter { it.usable }
                mutable.update { it.copy(message = when {
                    outcome.reachedTarget -> "enough"
                    outcome.timedOut -> "budget"
                    usable.isEmpty() -> "none"
                    else -> "done"
                }, phase = "done") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Keep a precise invalidation reason if the DataStore transaction raced an update.
                if (currentRun()) mutable.update { it.copy(message = "failed") }
                else mutable.update { it.copy(results = emptyList(), message =
                    if (stamp(physical()) != ticket.networkStamp) "network_changed" else "profile_changed") }
            }
            finally {
                clock.cancel()
                if (activeScan === ticket) activeScan = null
                mutable.update { it.copy(busy = false, stopping = false) }
            }
        }
    }
    fun stop() { if (mutable.value.busy && !mutable.value.stopping) {
        mutable.update { it.copy(message = "stopped", stopping = true) }
        scan?.cancel()
    } }
    fun select(ip: String?) {
        val current = mutable.value.profile ?: return
        val saved = record ?: return
        if (mutable.value.busy || applying || !saved.accepts(current) || (ip != null && saved.results.none { it.ip == ip })) return
        applying = true
        mutable.update { it.copy(applying = true) }
        viewModelScope.launch {
            try {
                prefs.saveCloudflareAddresses(current, saved.copy(selectedIp = ip), true)
                mutable.update { it.copy(selectedIp = ip, message = "selected") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "profile_changed") } }
            finally { applying = false; mutable.update { it.copy(applying = false) } }
        }
    }
    fun setKeepAddress(enabled: Boolean) {
        val current = mutable.value.profile ?: return
        if (mutable.value.busy || applying) return
        applying = true
        mutable.update { it.copy(applying = true) }
        viewModelScope.launch {
            try { prefs.setCloudflareKeepAddress(current, enabled) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "profile_changed") } }
            finally { applying = false; mutable.update { it.copy(applying = false) } }
        }
    }
    override fun onCleared() { activeScan?.invalidate(); scan?.cancel(); rangeRefresh?.cancel(); runCatching { cm.unregisterNetworkCallback(callback) } }
}
