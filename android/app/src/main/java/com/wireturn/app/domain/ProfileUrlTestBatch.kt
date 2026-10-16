package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.ConnectionCategory
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.listCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Frozen input, including mutable native-options JSON. Never include its contents in logs. */
class ProfileUrlTestSnapshot private constructor(
    val id: String,
    private val serialized: String,
    private val category: ConnectionCategory,
    val configDigest: String
) {
    fun matches(profile: Profile): Boolean = profile.id == id &&
        profile.listCategory == category &&
        gson.toJson(profile) == serialized

    internal fun profile(): Profile = gson.fromJson(serialized, Profile::class.java)

    companion object {
        private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
        internal fun capture(profile: Profile) = ProfileUrlTestSnapshot(profile.id, gson.toJson(profile), profile.listCategory, probeConfigDigest(profile))
    }
}

data class ProfileUrlTestResult(val snapshot: ProfileUrlTestSnapshot, val label: String, val measuredAt: Long? = null)

data class ProfileUrlTestState(
    val owner: String? = null,
    val busy: Boolean = false,
    val results: Map<String, ProfileUrlTestResult> = emptyMap(),
    val completed: Int = 0,
    val total: Int = 0
) {
    // Also guard the UI while its profile collector is ahead of the invalidation collector.
    fun labelFor(profile: Profile): String? = results[profile.id]?.takeIf { it.snapshot.matches(profile) }?.label
}

/** State mutations stay on the caller's (Main) dispatcher; probes own isolated processes. */
class ProfileUrlTestBatch(private val memberTimeoutMillis: Long = 75_000L, private val parallelism: Int = 4,
    private val onCompleted: (ProfileUrlTestResult) -> Unit = {}) {
    init { require(parallelism in 1..8) }
    private val mutable = MutableStateFlow(ProfileUrlTestState())
    val state = mutable.asStateFlow()
    private val activeProbes = mutableMapOf<ProfileUrlTestSnapshot, Deferred<String>>()
    private class ProfileChanged : CancellationException("URL-test profile changed")

    /** Must also be called after a batch has completed, so deleted/replaced results cannot return. */
    fun profilesChanged(profiles: List<Profile>) {
        val current = profiles.groupBy { it.id }
        fun matches(snapshot: ProfileUrlTestSnapshot) = current[snapshot.id]?.singleOrNull()?.let(snapshot::matches) == true
        mutable.value = mutable.value.copy(results = mutable.value.results.filterValues { matches(it.snapshot) })
        activeProbes.toMap().forEach { (snapshot, job) -> if (!matches(snapshot)) job.cancel(ProfileChanged()) }
    }

    suspend fun run(
        owner: String,
        candidates: List<Profile>,
        currentProfiles: () -> List<Profile>,
        concurrency: Int = parallelism,
        eligible: (Profile) -> Boolean = ProbeEligibility::allows,
        probe: suspend (Profile) -> Long
    ) {
        val runContext = currentCoroutineContext()
        runContext.ensureActive()
        if (mutable.value.busy) return
        profilesChanged(currentProfiles())
        val current = currentProfiles().groupBy { it.id }
        val members = candidates.filter(eligible)
            .distinctBy { it.id }.map(ProfileUrlTestSnapshot::capture)
            .filter { snapshot -> current[snapshot.id]?.singleOrNull()?.let(snapshot::matches) == true }
        if (members.isEmpty()) return
        mutable.value = ProfileUrlTestState(owner, true,
            mutable.value.results + members.associate { it.id to ProfileUrlTestResult(it, "Ожидание") }, total = members.size)
        try {
            val slots = Semaphore(concurrency.coerceIn(1, 8))
            coroutineScope { members.map { snapshot -> async { slots.withPermit {
                // Cancellation visits children in order. Releasing one child's permit can
                // resume a sibling before that sibling receives its cancellation signal.
                // Check the owner as well, so it cannot launch another native probe.
                runContext.ensureActive()
                currentCoroutineContext().ensureActive()
                profilesChanged(currentProfiles())
                if (isCurrent(snapshot)) {
                    result(snapshot, "Проверка…")
                    val label = measure(snapshot, probe)
                    runContext.ensureActive()
                    currentCoroutineContext().ensureActive()
                    profilesChanged(currentProfiles())
                    if (label != null && isCurrent(snapshot)) result(snapshot, label)
                }
                mutable.value = mutable.value.copy(completed = mutable.value.completed + 1)
            } } }.awaitAll() }
        } finally {
            profilesChanged(currentProfiles())
            mutable.value = mutable.value.copy(busy = false, results = mutable.value.results.mapValues { (_, value) ->
                if (value.label == "Ожидание" || value.label == "Проверка…") value.copy(label = "Отменено") else value
            })
        }
    }

    private suspend fun measure(snapshot: ProfileUrlTestSnapshot, probe: suspend (Profile) -> Long): String? = coroutineScope {
        // Hold the slot until cancellation has finished that member's tunnel cleanup.
        val worker = async(start = CoroutineStart.LAZY) {
            try {
                withTimeoutOrNull(memberTimeoutMillis) { "${probe(snapshot.profile())} мс" } ?: "Таймаут"
            } catch (e: TimeoutCancellationException) {
                // Readiness/preparation have their own withTimeout. They do not cancel this job,
                // unlike the user's cancellation or a deadline enclosing the entire batch.
                currentCoroutineContext().ensureActive()
                "Таймаут"
            } catch (e: CancellationException) {
                throw e
            } catch (_: ActiveWgProbeConflict) {
                currentCoroutineContext().ensureActive()
                "Аккаунт WG занят другим подключением"
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                "Ошибка URL-теста"
            }
        }
        activeProbes[snapshot] = worker
        try {
            worker.await()
        } catch (e: ProfileChanged) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            activeProbes.remove(snapshot)
        }
    }

    // Reference identity also binds the result to this run, even when its ID is reused.
    private fun isCurrent(snapshot: ProfileUrlTestSnapshot) = mutable.value.results[snapshot.id]?.snapshot === snapshot

    private fun result(snapshot: ProfileUrlTestSnapshot, label: String) {
        val value = ProfileUrlTestResult(snapshot, label,
            if (label in listOf("Ожидание", "Проверка…", "Отменено")) null else System.currentTimeMillis())
        if (value.measuredAt != null) onCompleted(value)
        mutable.value = mutable.value.copy(results = mutable.value.results + (snapshot.id to value))
    }
}
