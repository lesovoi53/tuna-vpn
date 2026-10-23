package com.wireturn.app.domain

import android.content.Context
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GroupRunState(
    val groupId: String? = null,
    val busy: Boolean = false,
    val automatic: Boolean = false,
    val selectedId: String? = null,
    val message: String = "",
    val results: List<GroupMeasurement> = emptyList(),
    val testingId: String? = null,
    val progress: ProbeProgress? = null,
    val testType: GroupTestType? = null
)

/** Measures disposable test instances without changing the active tunnel. Only connecting a
 * selected group can activate its winner through the normal service lifecycle. */
class ConnectionGroupRunner(
    private val runtime: GroupRuntime,
    private val scope: CoroutineScope,
    private val onMeasurement: (ConnectionGroup, Profile, GroupMeasurement, String) -> Unit = { _, _, _, _ -> },
    private val measure: suspend (ConnectionGroup, String, ClientConfig, (ProbeProgress) -> Unit) -> GroupMeasurement = GroupProbe::measure
) {
    companion object {
        @Volatile private var instance: ConnectionGroupRunner? = null
        fun get(context: Context): ConnectionGroupRunner = instance ?: synchronized(this) {
            instance ?: ConnectionGroupRunner(AndroidGroupRuntime(context.applicationContext),
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), onMeasurement = { group, profile, result, digest ->
                    ProbeHistoryStore.get(context).record(SavedProbeResult(profile.id,
                        ProbeKind.URL,
                        digest, result.measuredAt,
                        latencyMs = result.latencyMs.takeIf { result.error == null },
                        error = result.error, groupId = group.id,
                        endpointLabel = result.endpointLabel, selectionLatencyMs = result.selectionLatencyMs))
                }).also { instance = it }
        }
    }
    val state = MutableStateFlow(GroupRunState())
    private var job: Job? = null
    private var stoppingJob: Job? = null
    val hasPendingWork: Boolean get() = job?.isCompleted == false || stoppingJob?.isCompleted == false
    private var selection = GroupSelection()
    private var measuredProfiles: List<Profile>? = null
    private var measuredGroup: ConnectionGroup? = null
    private var measuredGroups: List<ConnectionGroup>? = null
    private var measuredNetwork: Set<String>? = null
    private var measuredSettings: GroupProbeSettings? = null
    @Volatile private var generation = 0L
    init {
        scope.launch {
            runtime.status.collect { if (it == CoreStatus.Idle && !state.value.busy) release() }
        }
    }

    fun release() {
        generation++
        if (job != null) stoppingJob = job
        job?.cancel(); job = null
        if (state.value.groupId != null) state.value = state.value.copy(busy = false, automatic = false, message = "Автовыбор остановлен; оставлен текущий профиль")
    }

    suspend fun stopAndJoin() {
        val pending = job ?: stoppingJob
        release()
        pending?.join()
    }

    /** A failed replacement must not strand an already working member of this group.
     * Never roll back a cancelled/user-stopped run or overwrite a concurrent profile edit. */
    private suspend fun activateRecovering(group: ConnectionGroup, target: Profile, anchor: Profile,
        previousId: String?, profiles: List<Profile>, token: Long) {
        val previous = profiles.firstOrNull { it.id == previousId && it.id != target.id && it.id in group.memberIds }
            ?.takeIf { runtime.status.value == CoreStatus.Connected && runtime.currentId() == it.id }
        val groups = runtime.groups()
        val network = runtime.network(true)
        try {
            runtime.activate(target, anchor)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            if (previous != null && generation == token && runtime.status.value != CoreStatus.Idle &&
                runtime.currentId() in setOf(previous.id, target.id) && runtime.profiles() == profiles &&
                runtime.groups() == groups && runtime.network(true) == network) {
                try {
                    withTimeout(15_000L) { runtime.activate(previous, anchor) }
                } catch (rollback: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (rollback is CancellationException && rollback !is TimeoutCancellationException) throw rollback
                }
            }
            throw error
        }
    }

    fun selectMember(group: ConnectionGroup, memberId: String) {
        if (!ManualGroupPolicy.allows(group)) return
        val previous = job
        val previousSelection = state.value.takeIf { it.groupId == group.id }?.selectedId
        val token = ++generation
        previous?.cancel()
        state.value = GroupRunState(group.id, true, false, previousSelection, "Ручной выбор сервера", testingId = memberId)
        job = scope.launch {
            try {
                previous?.join()
                val profiles = runtime.profiles()
                group.validateForTesting(profiles)
                require(memberId in group.memberIds)
                runtime.selectGroup(group.id)
                activateRecovering(group, profiles.first { it.id == memberId }, profiles.first { it.id == group.routingProfileId },
                    previousSelection, profiles, token)
                state.value = GroupRunState(group.id, false, false, memberId, "Сервер выбран вручную; автовыбор остановлен")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == token) state.value = state.value.copy(message = "Не удалось подключить выбранный сервер") }
            finally { if (generation == token) state.value = state.value.copy(busy = false, testingId = null) }
        }
    }

    fun connect(group: ConnectionGroup) = run(group, forceTest = false)
    fun test(group: ConnectionGroup) = run(group.copy(automatic = false), forceTest = true, connectAfterTest = false)

    fun run(group: ConnectionGroup, forceTest: Boolean = true, connectAfterTest: Boolean = true) {
        if (!ManualGroupPolicy.allows(group)) return
        val cleanup = stoppingJob
        if (job?.isActive == true) {
            if (!state.value.busy) scope.launch { job?.cancelAndJoin(); run(group, forceTest, connectAfterTest) }
            return
        }
        val previous = state.value.takeIf { it.groupId == group.id }?.selectedId?.takeIf { it in group.memberIds }
        val priorResults = state.value.results.takeIf { state.value.groupId == group.id }.orEmpty()
        val runGeneration = ++generation
        if (previous == null) selection = GroupSelection()
        state.value = GroupRunState(group.id, true, group.automatic, previous, "Подготовка теста", testType = group.type)
        job = scope.launch {
            var selected = previous
            var routingApplied = false
            try {
                cleanup?.join()

                val profilesBefore = runtime.profiles()
                group.validateForTesting(profilesBefore)
                if (connectAfterTest) runtime.selectGroup(group.id)
                val groupsBefore = runtime.groups()
                val allowProbeMobile = group.allowMobile
                val networkBefore = runtime.network(allowProbeMobile)
                val probeSettingsBefore = runtime.probeSettings(profilesBefore.filter { it.id in group.memberIds })
                val cacheMatches = profilesBefore == measuredProfiles && groupsBefore == measuredGroups && networkBefore == measuredNetwork &&
                    probeSettingsBefore == measuredSettings && group.copy(automatic = false) == measuredGroup
                if (!cacheMatches) { selected = null; selection = GroupSelection() }
                val cached = priorResults.takeIf { cacheMatches }.orEmpty().find { it.profileId == previous && it.error == null &&
                    System.currentTimeMillis() - it.measuredAt in 0..group.freshnessSeconds * 1000L }
                if (!forceTest && !group.testOnConnect && cached != null) {
                    val profiles = runtime.profiles()
                    group.validateForTesting(profiles)
                    suspend fun checkCachedSnapshot() {
                        ensureActive()
                        require(runtime.network(allowProbeMobile) == networkBefore) { "Сеть изменилась" }
                        require(runtime.profiles() == profilesBefore && runtime.groups() == groupsBefore) { "Настройки группы изменились" }
                        require(runtime.probeSettings(profiles.filter { it.id in group.memberIds }) == probeSettingsBefore) { "Настройки DNS или маршрутизации изменились: повторите тест" }
                    }
                    checkCachedSnapshot()
                    runtime.activate(profiles.first { it.id == previous }, profiles.first { it.id == group.routingProfileId })
                    routingApplied = true
                    state.value = GroupRunState(group.id, false, group.automatic, previous, "Подключён ранее выбранный сервер", priorResults, testType = group.type)
                    if (!group.automatic) return@launch
                    delay(group.intervalSeconds * 1000L)
                }
                do {
                    val profiles = runtime.profiles()
                    group.validateForTesting(profiles)
                    require(group.memberIds.size >= 2) { "Выберите минимум два сервера" }
                    val groupSnapshot = runtime.groups()
                    val networkIds = runtime.network(allowProbeMobile)
                    val members = profiles.filter { it.id in group.memberIds }
                    val probeSettings = runtime.probeSettings(members)
                    val anchor = profiles.first { it.id == group.routingProfileId }
                    val results = mutableListOf<GroupMeasurement>()
                    suspend fun checkSnapshot() {
                        ensureActive()
                        require(runtime.profiles() == profiles) { "Профили изменились: запустите тест заново" }
                        require(runtime.groups() == groupSnapshot) { "Группы изменились: запустите тест заново" }
                        require(runtime.network(allowProbeMobile) == networkIds) { "Сеть изменилась: запустите сравнение заново" }
                        require(runtime.probeSettings(members) == probeSettings) { "Настройки DNS или маршрутизации изменились: повторите тест" }
                    }
                    fun failure(id: String, error: Exception): GroupMeasurement {
                        ensureActive()
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        return GroupMeasurement(id, 0, measuredAt = System.currentTimeMillis(), error = when (error) {
                            is TimeoutCancellationException -> "Таймаут подключения"
                            is ActiveWgProbeConflict -> error.message
                            else -> "Тест не пройден: ${error.javaClass.simpleName}"
                        })
                    }
                    fun record(result: GroupMeasurement) {
                        val profile = profiles.first { it.id == result.profileId }
                        val digest = probeConfigDigest(profile, com.google.gson.Gson().toJson(probeSettings.fragmentation))
                        onMeasurement(group, profile, result, digest)
                    }
                    state.value = GroupRunState(group.id, true, group.automatic, selected, "Проверка соединений", testType = group.type)
                    for (id in group.memberIds) {
                        checkSnapshot()
                        val p = profiles.first { it.id == id }
                        state.value = state.value.copy(message = "Подключение: ${p.name}", results = results.toList(), testingId = id, progress = null)
                        val result = try {
                            runtime.withTestTunnel(p) { cfg ->
                                checkSnapshot()
                                state.value = state.value.copy(message = "Измерение: ${p.name}")
                                measure(group, id, cfg) { progress ->
                                    if (generation == runGeneration) state.update { it.copy(progress = progress) }
                                }
                            }
                        } catch (e: Exception) { failure(id, e) }
                        checkSnapshot()
                        results += result
                        record(results.last())
                        state.value = state.value.copy(results = results.toList(), testingId = null, progress = null)
                    }
                    checkSnapshot()
                    measuredProfiles = profiles
                    measuredGroup = group.copy(automatic = false)
                    measuredGroups = groupSnapshot
                    measuredNetwork = networkIds
                    measuredSettings = probeSettings
                    val winner = selection.choose(group, results, if (connectAfterTest) selected else null, System.currentTimeMillis())
                    if (!connectAfterTest) {
                        state.value = GroupRunState(group.id, false, false, winner, "Проверка завершена. Подключение не изменено", results, testType = group.type)
                        break
                    }
                    if (winner == null) {
                        state.value = GroupRunState(group.id, false, group.automatic, selected,
                            "Все серверы недоступны. Подключение не изменено", results, testType = group.type)
                    } else {
                        state.value = state.value.copy(message = "Подключение выбранного сервера", testingId = winner)
                        var activated = true
                        try {
                            if (!routingApplied || runtime.currentId() != winner || runtime.status.value != CoreStatus.Connected)
                                activateRecovering(group, profiles.first { it.id == winner }, anchor, selected ?: previous,
                                    profiles, runGeneration)
                            routingApplied = true
                        }
                        catch (_: Exception) { ensureActive(); activated = false }
                        val changed = winner != selected
                        if (activated) selected = winner
                        state.value = GroupRunState(group.id, false, group.automatic, selected,
                            if (!activated) "Не удалось восстановить туннель: нужна повторная проверка"
                            else selection.decisionMessage, results, testType = group.type)
                    }
                    if (!group.automatic) break
                    delay(group.intervalSeconds * 1000L)
                    if (runtime.status.value == CoreStatus.Idle) break
                } while (isActive)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = state.value.copy(busy = false, automatic = false, message = e.message ?: "Ошибка группы") }
            finally {
                if (generation == runGeneration) state.value = state.value.copy(busy = false, automatic = false, testingId = null, progress = null)
            }
        }
    }

}
