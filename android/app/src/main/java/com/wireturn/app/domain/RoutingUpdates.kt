package com.wireturn.app.domain

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RoutingUpdates {
    private const val JOB = 7241
    private val mutex = Mutex()
    fun preferences(context: Context) = context.getSharedPreferences("routing_updates", Context.MODE_PRIVATE)
    fun intervalHours(context: Context) = preferences(context).getInt("interval", 24).coerceIn(1, 720)
    fun schedule(context: Context, hours: Int = intervalHours(context)) {
        require(hours in 1..720)
        preferences(context).edit().putInt("interval", hours).apply()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val interval = hours * 3600000L
        if (scheduler.getPendingJob(JOB)?.intervalMillis == interval) return
        scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, RoutingUpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(interval).build())
    }

    suspend fun run(context: Context, force: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val state = preferences(context)
            // Offline retention must also run when a network refresh is not due.
            try {
                GeoAssetsManager.maintainCatalog(context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.edit().putString("error", "Не удалось очистить старые версии геобаз: ${e.message ?: "ошибка хранилища"}").apply()
                return@withContext false
            }
            val now = System.currentTimeMillis()
            val due = force || now - state.getLong("last_success", 0) >= intervalHours(context) * 3600000L ||
                !GeoAssetsManager.filesExist(context) || !GeoAssetsManager.allProvidersCached(context)
            if (!due) return@withContext true
            if (!force && now - state.getLong("last_attempt", 0) < 15 * 60_000L) return@withContext false
            state.edit().putLong("last_attempt", now).apply()
            state.edit().putBoolean("running", true).putString("error", "").apply()
            try {
                val prefs = AppPreferences(context)
                prefs.migrateRoutingOwnership()
                val common = prefs.xraySettingsFlow.first()
                val manager = GeoAssetsManager(context)
                manager.download(GeoResourceSet.fromId(common.geoVariant))
                val errors = mutableListOf<String>()
                if (manager.state.value !is com.wireturn.app.viewmodel.GeoAssetsState.Success)
                    errors += (manager.state.value as? com.wireturn.app.viewmodel.GeoAssetsState.Error)?.message ?: "Базы не обновлены"
                val sources = prefs.subscriptionsFlow.first().mapNotNull { it.routingSource?.takeIf(String::isNotBlank) }.toMutableSet()
                common.happRoutingSource.takeIf(String::isNotBlank)?.let(sources::add)
                for (source in sources) {
                    try { SubscriptionRoutingAssets.prepare(context, source, force = true) }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        errors += e.message ?: "Ошибка базы подписки"
                    }
                }
                // Include all offered providers even on a fresh install with no subscriptions.
                // A failed provider must not prevent the remaining downloads.
                for (variant in GeoResourceSet.entries) {
                    try { GeoAssetsManager.prefetch(context, variant, if (force) 0 else intervalHours(context) * 3600000L) }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        errors += "${variant.id}: ${e.message}"
                    }
                }
                check(errors.isEmpty()) { errors.joinToString("; ") }
                state.edit().putLong("last_success", System.currentTimeMillis()).apply()
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.edit().putString("error", e.message ?: "Не удалось обновить базы").apply()
                false
            } finally { state.edit().putBoolean("running", false).apply() }
        }
    }
}

class RoutingUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch { val ok = RoutingUpdates.run(applicationContext); jobFinished(params, !ok) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
