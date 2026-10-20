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
    val selected: Set<String> = emptySet(),
    val measuredAt: Long? = null,
    val scanComplete: Boolean = false,
    val busy: Boolean = false,
    val comparing: Boolean = false,
    val poolMeasurements: List<MasterDnsPoolMeasurement> = emptyList(),
    val applying: Boolean = false,
    val reconnectsOnApply: Boolean = false,
    val progress: String = "",
    val message: String? = null,
    val error: String? = null
)

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

    private fun restoreResults() {
        cacheRead?.cancel()
        mutable.update { it.copy(results = emptyList(), selected = emptySet(), measuredAt = null,
            poolMeasurements = emptyList()) }
        val profile = mutable.value.profile ?: return
        val network = mutable.value.networkStamp ?: return
        val expected = MasterDnsScanCache.profileStamp(profile)
        cacheRead = viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { MasterDnsScanCache.load(resultsDir, profile, network) }
            if (mutable.value.profile?.let(MasterDnsScanCache::profileStamp) != expected || mutable.value.networkStamp != network ||
                mutable.value.busy) return@launch
            val preferred = (profile.kernelConfig as KernelConfig.MasterDns).config.localResolvers.orEmpty().lines().toSet()
            mutable.update { it.copy(results = saved?.results.orEmpty(), measuredAt = saved?.measuredAt, scanComplete = saved?.complete == true,
                poolMeasurements = saved?.poolMeasurements.orEmpty(),
                selected = saved?.results.orEmpty().filter { result -> result.valid && result.resolver in preferred }.map { it.resolver }.toSet()) }
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

    fun scan() {
        if (mutable.value.busy || mutable.value.applying) return
        val profile = mutable.value.profile ?: return
        val network = mutable.value.networks.find { it.stamp == mutable.value.networkStamp } ?: return
        val catalogue = mutable.value.catalogue ?: return
        val config = (profile.kernelConfig as KernelConfig.MasterDns).config
        val token = MasterDnsScanCache.profileStamp(profile) to network.stamp
        scanStamp = token
        cacheRead?.cancel()
        mutable.update { it.copy(busy = true, progress = "Подготовка проверки…", results = emptyList(),
            poolMeasurements = emptyList(),
            selected = emptySet(), measuredAt = null, scanComplete = false, error = null, message = null) }
        work = viewModelScope.launch {
            try {
                MasterDnsResolverCheck.scan(getApplication(), config, network.network,
                    (config.resolverList() + catalogue.endpoints.map { it.value }).distinct(),
                    onProgress = { progress -> if (scanStamp == token) mutable.update { it.copy(progress = progress) } },
                    onResult = { result -> if (scanStamp == token) mutable.update { it.copy(results =
                        it.results.filterNot { old -> old.resolver == result.resolver && old.domain == result.domain } + result) } })
                ensureActive()
                check(scanStamp == token && physicalNetworks().any { it.stamp == token.second }) { "Сеть изменилась" }
                val record = MasterDnsSavedScan(profileStamp = token.first, networkStamp = token.second,
                    measuredAt = System.currentTimeMillis(), results = mutable.value.results)
                withContext(Dispatchers.IO) { MasterDnsScanCache.save(resultsDir, profile, record) }
                mutable.update { it.copy(measuredAt = record.measuredAt, scanComplete = true,
                    message = "Проверка завершена. Выберите резолверы для этого профиля.") }
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
                val usable = mutable.value.results.any { it.valid && it.attempts == 5 }
                if (usable && scanStamp == token && physicalNetworks().any { it.stamp == token.second } &&
                    mutable.value.profile?.let(MasterDnsScanCache::profileStamp) == token.first) {
                    val partial = MasterDnsSavedScan(profileStamp = token.first, networkStamp = token.second,
                        measuredAt = System.currentTimeMillis(), results = mutable.value.results, complete = false)
                    try {
                        withContext(Dispatchers.IO) { MasterDnsScanCache.save(resultsDir, profile, partial) }
                        mutable.update { it.copy(error = failureMessage, measuredAt = partial.measuredAt,
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
        mutable.update { it.copy(selected = if (selected) it.selected + resolver else it.selected - resolver, message = null) }
    }

    fun recommend() {
        if (mutable.value.busy || mutable.value.applying) return
        val winner = MasterDnsResolverRecommendation.winner(mutable.value.poolMeasurements)
        val selected = (winner?.resolvers ?: MasterDnsResolverRecommendation.recommend(mutable.value.results)).toSet()
        mutable.update { it.copy(selected = selected, error = null,
            message = if (selected.isEmpty()) "Нет резолверов, прошедших проверку."
                else if (winner != null) "Отмечен набор по результатам передачи данных. Примените выбранные адреса."
                else "Отмечено ${selected.size} адресов по MTU, потерям и задержке. Скорость ещё не измерена; можно сравнить наборы.") }
    }

    fun comparePools() {
        val snapshot = mutable.value
        if (snapshot.busy || snapshot.applying) return
        val profile = snapshot.profile ?: return
        val network = snapshot.networks.find { it.stamp == snapshot.networkStamp } ?: return
        val measuredAt = snapshot.measuredAt ?: return
        val config = (profile.kernelConfig as KernelConfig.MasterDns).config
        val token = MasterDnsScanCache.profileStamp(profile) to network.stamp
        val record = MasterDnsSavedScan(profileStamp = token.first, networkStamp = token.second,
            measuredAt = measuredAt, results = snapshot.results, complete = snapshot.scanComplete)
        if (!record.accepts(profile, network.stamp)) {
            mutable.update { it.copy(error = "Результаты устарели. Сначала повторите сканирование.") }; return
        }
        val pools = MasterDnsResolverRecommendation.candidatePools(snapshot.results,
            snapshot.selected.toList().ifEmpty { config.resolverList() })
        if (pools.isEmpty()) return
        scanStamp = token
        cacheRead?.cancel()
        mutable.update { it.copy(busy = true, comparing = true, poolMeasurements = emptyList(),
            progress = "Подготовка сравнения наборов…", error = null, message = null) }
        work = viewModelScope.launch {
            var comparisonSaved = false
            try {
                val measurements = mutableListOf<MasterDnsPoolMeasurement>()
                withTimeout(13 * 60_000L) {
                    for ((index, pool) in pools.withIndex()) {
                        ensureActive()
                        check(scanStamp == token && physicalNetworks().any { it.stamp == token.second } &&
                            mutable.value.profile?.let(MasterDnsScanCache::profileStamp) == token.first) { "Сеть или профиль изменились" }
                        val before = MasterDnsResolverRecommendation.winner(measurements)
                        val measurement = try {
                            val result = MasterDnsGoodput.measure(getApplication(), config, network.network, pool,
                                onProgress = { progress -> if (scanStamp == token) mutable.update { it.copy(progress =
                                    "Набор ${index + 1}/${pools.size} · ${pool.size} адресов\n${progress.phase}\n" +
                                        "Данные: ${progress.payloadBytes / 1024} КиБ · DNS-трафик: ${progress.wireBytes / 1024} КиБ") } })
                            MasterDnsPoolMeasurement(pool, result.payloadBytes, result.elapsedMs,
                                result.wireBytes, result.effectiveParameters)
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (e is CancellationException && e !is TimeoutCancellationException) throw e
                            MasterDnsPoolMeasurement(pool, 0, 0,
                                error = if (e is TimeoutCancellationException) "Истекло время проверки набора"
                                    else "Набор не измерен: ${e.javaClass.simpleName}")
                        }
                        ensureActive()
                        check(scanStamp == token && physicalNetworks().any { it.stamp == token.second })
                        measurements += measurement
                        mutable.update { it.copy(poolMeasurements = measurements.toList()) }
                        // Stop growing a successful smaller pool once the next measured size
                        // no longer improves throughput. A failed trial is not a speed result.
                        if (before != null && measurement.successful && pool.size > before.resolvers.size &&
                            measurement.bytesPerSecond <= before.bytesPerSecond * 1.10) break
                    }
                }
                ensureActive()
                check(scanStamp == token && physicalNetworks().any { it.stamp == token.second } &&
                    mutable.value.profile?.let(MasterDnsScanCache::profileStamp) == token.first)
                withContext(Dispatchers.IO) {
                    MasterDnsScanCache.save(resultsDir, profile, record.copy(poolMeasurements = measurements))
                }
                comparisonSaved = true
                val winner = MasterDnsResolverRecommendation.winner(measurements)
                mutable.update { it.copy(selected = winner?.resolvers?.toSet() ?: it.selected,
                    message = if (winner == null) "Передачу данных измерить не удалось. Предварительный выбор доступен по MTU, потерям и задержке."
                        else "Выбран набор: ${winner.resolvers.size} адресов. Результат одной короткой загрузки; примените выбранные адреса.") }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(error = "Истекло время сравнения. Повторите проверку.") }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Сравнение не сохранено: сеть, профиль или срок результатов изменились.") }
            } finally {
                scanStamp = null
                mutable.update { it.copy(busy = false, comparing = false, progress = "",
                    poolMeasurements = if (comparisonSaved) it.poolMeasurements else snapshot.poolMeasurements) }
                if (mutable.value.profile?.let(MasterDnsScanCache::profileStamp) != token.first ||
                    mutable.value.networkStamp != token.second) restoreResults()
            }
        }
    }

    fun setSource(local: Boolean) {
        val profile = mutable.value.profile ?: return
        val config = (profile.kernelConfig as KernelConfig.MasterDns).config
        persistSelection(profile, config.localResolvers, local)
    }

    fun applySelection() {
        val s = mutable.value
        val profile = s.profile ?: return
        val network = s.networkStamp ?: return
        val saved = MasterDnsSavedScan(profileStamp = MasterDnsScanCache.profileStamp(profile),
            networkStamp = network, measuredAt = s.measuredAt ?: return, results = s.results)
        if (!saved.accepts(profile, network) || physicalNetworks().none { it.stamp == network }) {
            mutable.update { it.copy(error = "Результаты устарели или сеть изменилась. Повторите проверку.") }; return
        }
        val valid = s.results.filter { it.valid }.map { it.resolver }.toSet()
        if (s.selected.isEmpty() || s.selected.size > 128 || !valid.containsAll(s.selected)) return
        persistSelection(profile, s.selected.joinToString("\n"), true)
    }

    private fun persistSelection(profile: Profile, local: String?, enabled: Boolean) {
        if (mutable.value.busy || mutable.value.applying) return
        mutable.update { it.copy(applying = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                prefs.saveMasterDnsResolverSelection(profile, local, enabled)
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
