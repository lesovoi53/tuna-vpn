package com.wireturn.app.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class MasterDnsNetwork(val network: Network, val stamp: String, val label: String)
data class MasterDnsResolverScreenState(
    val profiles: List<Profile> = emptyList(),
    val profile: Profile? = null,
    val networks: List<MasterDnsNetwork> = emptyList(),
    val networkStamp: String? = null,
    val catalogue: MasterDnsCatalogSnapshot? = null,
    val results: List<MasterDnsResolverResult> = emptyList(),
    val activeDomain: String? = null,
    val selectedByDomain: Map<String, Set<String>> = emptyMap(),
    val domainMeasuredAt: Map<String, Long> = emptyMap(),
    val measuredAt: Long? = null,
    val scanComplete: Boolean = false,
    val busy: Boolean = false,
    val applying: Boolean = false,
    val reconnectsOnApply: Boolean = false,
    val progress: String = "",
    val message: String? = null,
    val error: String? = null
) {
    val selected: Set<String> get() = selectedByDomain[activeDomain].orEmpty()
    val domainResults: List<MasterDnsResolverResult> get() = results.filter { it.domain == activeDomain }
    val activeMeasuredAt: Long? get() = domainMeasuredAt[activeDomain]
}

class MasterDnsResolverViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = AppPreferences(application)
    private val cm = application.getSystemService(ConnectivityManager::class.java)
    private val catalog = MasterDnsCatalog(File(application.filesDir, "masterdns-catalog"))
    private val resultsDir = File(application.cacheDir, "masterdns-results")
    private val mutable = MutableStateFlow(MasterDnsResolverScreenState())
    val state = mutable.asStateFlow()
    private var profileId: String? = null
    private var observer: Job? = null
    private var work: Job? = null
    private var cacheRead: Job? = null
    private var scanStamp: Pair<String, String>? = null
    private var callbackRegistered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = networkChanged()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = networkChanged()
    }

    init {
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            callbackRegistered = true
        } catch (_: Exception) {
            mutable.update { it.copy(error = "Не удалось отслеживать физическую сеть") }
        }
        networkChanged()
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { catalog.load() }
                mutable.update { it.copy(catalogue = saved) }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = "Не удалось прочитать базу DNS. Обновите её или импортируйте TXT.") } }
        }
    }

    private fun physicalNetworks(): List<MasterDnsNetwork> = cm.allNetworks.mapNotNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
        val props = cm.getLinkProperties(network)
        val stamp = MasterDnsScanCache.digest("${network.networkHandle}:${props?.interfaceName}:" +
            "${props?.linkAddresses?.map { it.toString() }?.sorted()}:${props?.dnsServers?.mapNotNull { it.hostAddress }?.sorted()}")
        val label = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Другая сеть"
        }
        MasterDnsNetwork(network, stamp, label)
    }.sortedBy { if (it.label == "Wi-Fi") 0 else 1 }

    private fun networkChanged() { viewModelScope.launch {
        val networks = physicalNetworks()
        val old = mutable.value.networkStamp
        val next = networks.firstOrNull { it.stamp == old }?.stamp ?: networks.firstOrNull()?.stamp
        mutable.update { it.copy(networks = networks, networkStamp = next) }
        if (old != next) {
            if (scanStamp != null) {
                scanStamp = null
                work?.cancel()
                mutable.update { it.copy(message = "Сеть изменилась. Проверка остановлена.") }
            }
            restoreResults()
        }
    } }

    fun load(id: String) {
        if (observer != null) return
        profileId = id
        observer = viewModelScope.launch {
            combine(prefs.profilesFlow, prefs.currentProfileIdFlow, com.wireturn.app.CoreServiceState.isRunning) {
                all, currentId, running -> Triple(all, currentId, running)
            }.collect { (all, currentId, running) ->
                val profiles = all.filter { it.kernelConfig is KernelConfig.MasterDns }
                val profile = profiles.find { it.id == profileId }
                val before = mutable.value.profile?.let(MasterDnsScanCache::profileStamp)
                val after = profile?.let(MasterDnsScanCache::profileStamp)
                mutable.update { it.copy(profiles = profiles, profile = profile,
                    reconnectsOnApply = profile != null && profile.id == currentId && running) }
                if (before != after) {
                    if (scanStamp != null) {
                        scanStamp = null
                        work?.cancel()
                        mutable.update { it.copy(message = "Профиль изменился. Выполните проверку заново.") }
                    }
                    restoreResults()
                }
            }
        }
    }

    fun selectProfile(id: String) {
        if (mutable.value.busy || mutable.value.applying) return
        profileId = id
        mutable.update { it.copy(profile = it.profiles.find { profile -> profile.id == id }, message = null, error = null) }
        viewModelScope.launch {
            val active = prefs.currentProfileIdFlow.first()
            mutable.update { it.copy(reconnectsOnApply = it.profile?.id == active && com.wireturn.app.CoreServiceState.isRunning.value) }
        }
        restoreResults()
    }

    fun selectNetwork(stamp: String) {
        if (mutable.value.busy || mutable.value.applying || mutable.value.networks.none { it.stamp == stamp }) return
        mutable.update { it.copy(networkStamp = stamp, message = null, error = null) }
        restoreResults()
    }

    fun selectDomain(domain: String) {
        val config = (mutable.value.profile?.kernelConfig as? KernelConfig.MasterDns)?.config ?: return
        if (domain !in config.domains) return
        mutable.update { it.copy(activeDomain = domain, message = null, error = null) }
    }

    private fun restoreResults() {
        cacheRead?.cancel()
        val profile = mutable.value.profile
        val config = (profile?.kernelConfig as? KernelConfig.MasterDns)?.config
        val selections = config?.let(MasterDnsDomainSelection::saved).orEmpty()
        mutable.update { it.copy(results = emptyList(), selectedByDomain = selections, measuredAt = null,
            domainMeasuredAt = emptyMap(), scanComplete = false,
            activeDomain = it.activeDomain?.takeIf { domain -> domain in config?.domains.orEmpty() } ?: config?.domains?.firstOrNull()) }
        profile ?: return
        val network = mutable.value.networkStamp ?: return
        val expected = MasterDnsScanCache.profileStamp(profile)
        cacheRead = viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { MasterDnsScanCache.load(resultsDir, profile, network) }
            if (mutable.value.profile?.let(MasterDnsScanCache::profileStamp) != expected || mutable.value.networkStamp != network ||
                mutable.value.busy) return@launch
            val results = saved?.results.orEmpty().mapNotNull { result ->
                config?.let { MasterDnsDomainSelection.profileDomain(it, result.domain) }?.let { result.copy(domain = it) }
            }
            val times = saved?.measurementTimes().orEmpty().mapNotNull { (domain, time) ->
                config?.let { MasterDnsDomainSelection.profileDomain(it, domain) }?.let { it to time }
            }.toMap()
            mutable.update { it.copy(results = results, measuredAt = saved?.measuredAt, scanComplete = saved?.complete == true,
                domainMeasuredAt = times) }
        }
    }

    fun updateCatalogue() = catalogueWork("Загрузка базы DNS…") {
        catalog.update { progress -> mutable.update { it.copy(progress =
            "${progress.source} · ${progress.completed} из ${progress.total} источников") } }
    }

    fun importCatalogue(uri: Uri) = catalogueWork("Импорт списка DNS…") {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { catalog.importList(it) }
            ?: error("Не удалось открыть файл")
    }

    private fun catalogueWork(title: String, block: suspend () -> MasterDnsCatalogSnapshot) {
        if (mutable.value.busy || mutable.value.applying) return
        mutable.update { it.copy(busy = true, progress = title, message = null, error = null) }
        work = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                mutable.update { it.copy(catalogue = result, message =
                    if (result.partial) "База обновлена частично. Проверьте состояние источников."
                    else "База сохранена и доступна офлайн.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Не удалось обновить базу. Предыдущая база сохранена.") } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }

    fun scan(domain: String? = null) {
        if (mutable.value.busy || mutable.value.applying) return
        val profile = mutable.value.profile ?: return
        val network = mutable.value.networks.find { it.stamp == mutable.value.networkStamp } ?: return
        val catalogue = mutable.value.catalogue ?: return
        val config = (profile.kernelConfig as KernelConfig.MasterDns).config
        if (domain != null && domain !in config.domains) return
        val scanningDomains = if (domain == null) config.domains.toSet() else setOf(domain)
        val scanConfig = config.copy(domains = scanningDomains.toList())
        val token = MasterDnsScanCache.profileStamp(profile) to network.stamp
        scanStamp = token
        cacheRead?.cancel()
        mutable.update { it.copy(busy = true, progress = "Подготовка проверки…", results = it.results.filterNot { r -> r.domain in scanningDomains },
            domainMeasuredAt = it.domainMeasuredAt - scanningDomains, scanComplete = false, error = null, message = null) }
        work = viewModelScope.launch {
            val probeTimes = mutableMapOf<String, Long>()
            try {
                MasterDnsResolverCheck.scan(getApplication(), scanConfig, network.network,
                    (config.profileResolverList() + scanningDomains.flatMap { config.localResolverList(it) } +
                        catalogue.endpoints.map { it.value }).distinct(),
                    onProgress = { progress -> if (scanStamp == token) mutable.update { it.copy(progress = progress) } },
                    onResult = { result ->
                        val domain = MasterDnsDomainSelection.profileDomain(config, result.domain)
                        if (scanStamp == token && domain != null) {
                            probeTimes[domain] = System.currentTimeMillis()
                            mutable.update { it.copy(results =
                                it.results.filterNot { old -> old.resolver == result.resolver && old.domain == domain } + result.copy(domain = domain)) }
                        }
                    })
                ensureActive()
                check(scanStamp == token && physicalNetworks().any { it.stamp == token.second }) { "Сеть изменилась" }
                val now = System.currentTimeMillis()
                val times = mutable.value.domainMeasuredAt + scanningDomains.associateWith { probeTimes[it] ?: now }
                val record = MasterDnsSavedScan(profileStamp = token.first, networkStamp = token.second,
                    measuredAt = now, results = mutable.value.results, domainMeasuredAt = times)
                withContext(Dispatchers.IO) { MasterDnsScanCache.save(resultsDir, profile, record) }
                mutable.update { it.copy(measuredAt = record.measuredAt, domainMeasuredAt = times, scanComplete = true,
                    message = "Проверка базы завершена. Выберите рабочие резолверы отдельно для каждого домена.") }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // A child timeout leaves this coroutine active. User/network/profile
                    // cancellation does not, and must never make partial results applicable.
                    currentCoroutineContext().ensureActive()
                    if (e !is TimeoutCancellationException) throw e
                }
                val failureMessage = if (e is TimeoutCancellationException) "Истекло время проверки"
                    else e.message ?: "Проверка не завершена"
                // Completed per-pair probes remain usable when the overall scan deadline expires.
                // Never retain measurements invalidated by a network/profile transition.
                val usable = mutable.value.results.any { it.domain in scanningDomains && it.valid && it.attempts == 5 }
                if (usable && scanStamp == token && physicalNetworks().any { it.stamp == token.second } &&
                    mutable.value.profile?.let(MasterDnsScanCache::profileStamp) == token.first) {
                    val now = System.currentTimeMillis()
                    val times = mutable.value.domainMeasuredAt + probeTimes
                    val partial = MasterDnsSavedScan(profileStamp = token.first, networkStamp = token.second,
                        measuredAt = now, results = mutable.value.results, complete = false, domainMeasuredAt = times)
                    try {
                        withContext(Dispatchers.IO) { MasterDnsScanCache.save(resultsDir, profile, partial) }
                        mutable.update { it.copy(error = failureMessage, measuredAt = partial.measuredAt, domainMeasuredAt = times,
                            scanComplete = false, message = "Можно выбрать адреса с завершёнными туннельными пробами. Остальные не проверены.") }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        mutable.update { it.copy(error = "Не удалось сохранить результаты проверки. Повторите проверку.", measuredAt = null) }
                    }
                } else mutable.update { it.copy(error = failureMessage, measuredAt = null) }
            }
            finally {
                scanStamp = null
                mutable.update { it.copy(busy = false, progress = "") }
                if (mutable.value.profile?.let(MasterDnsScanCache::profileStamp) != token.first ||
                    mutable.value.networkStamp != token.second) restoreResults()
            }
        }
    }

    fun stop() {
        if (!mutable.value.busy) return
        scanStamp = null
        work?.cancel()
        mutable.update { it.copy(message = "Операция остановлена. Выбор резолверов не изменён.") }
    }

    fun toggle(resolver: String, selected: Boolean) {
        if (mutable.value.busy || mutable.value.applying) return
        val s = mutable.value
        val domain = s.activeDomain ?: return
        if (selected && (s.activeMeasuredAt == null || s.domainResults.none { it.resolver == resolver && it.valid })) return
        mutable.update { it.copy(selectedByDomain = it.selectedByDomain +
            (domain to if (selected) it.selected + resolver else it.selected - resolver), message = null) }
    }

    fun clearSelection() {
        if (mutable.value.busy || mutable.value.applying) return
        val domain = mutable.value.activeDomain ?: return
        mutable.update { it.copy(selectedByDomain = it.selectedByDomain + (domain to emptySet()), message = null) }
    }

    fun selectWorking() {
        if (mutable.value.busy || mutable.value.applying) return
        val s = mutable.value
        val domain = s.activeDomain ?: return
        val time = s.activeMeasuredAt ?: return
        val now = System.currentTimeMillis()
        if (now < time || now - time > MasterDnsScanCache.MAX_AGE_MS) {
            mutable.update { it.copy(error = "Результаты устарели. Повторите проверку базы.") }; return
        }
        val selected = MasterDnsDomainSelection.working(s.results, domain)
        mutable.update { it.copy(selectedByDomain = it.selectedByDomain + (domain to selected), error = null,
            message = if (selected.isEmpty()) "Нет резолверов, прошедших проверку для $domain."
                else "Отмечены все рабочие адреса для $domain: ${selected.size}. Примените выбранные адреса.") }
    }

    fun setSource(local: Boolean) {
        val profile = mutable.value.profile ?: return
        val config = (profile.kernelConfig as KernelConfig.MasterDns).config
        persistSelection(profile, config.localResolvers, local, config.localDomainResolvers)
    }

    fun applySelection() {
        val s = mutable.value
        val profile = s.profile ?: return
        val config = (profile.kernelConfig as KernelConfig.MasterDns).config
        val domain = s.activeDomain ?: return
        if (s.selected.isNotEmpty()) {
            val network = s.networkStamp ?: return
            val measuredAt = s.activeMeasuredAt ?: return
            val now = System.currentTimeMillis()
            if (now < measuredAt || now - measuredAt > MasterDnsScanCache.MAX_AGE_MS ||
                physicalNetworks().none { it.stamp == network }) {
                mutable.update { it.copy(error = "Результаты устарели или сеть изменилась. Повторите проверку.") }; return
            }
            val valid = s.domainResults.filter { it.valid }.map { it.resolver }.toSet()
            if (s.selected.size > 128 || !valid.containsAll(s.selected)) return
        }
        val domains = try { MasterDnsDomainSelection.merge(config, domain, s.selected) }
        catch (_: IllegalArgumentException) {
            mutable.update { it.copy(error = "Оставьте хотя бы один домен с резолверами.") }; return
        }
        persistSelection(profile, config.localResolvers, true, domains)
    }

    private fun persistSelection(profile: Profile, local: String?, enabled: Boolean, domains: Map<String, String>?) {
        if (mutable.value.busy || mutable.value.applying) return
        mutable.update { it.copy(applying = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                prefs.saveMasterDnsResolverSelection(profile, local, enabled, domains)
                mutable.update { it.copy(message = "Выбор сохранён для этого профиля.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = "Профиль изменился или выбранный набор недопустим. Повторите действие.") } }
            finally { mutable.update { it.copy(applying = false) } }
        }
    }

    override fun onCleared() {
        work?.cancel()
        cacheRead?.cancel()
        if (callbackRegistered) cm.unregisterNetworkCallback(callback)
    }
}
