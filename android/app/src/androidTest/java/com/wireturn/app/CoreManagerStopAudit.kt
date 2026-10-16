package com.wireturn.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.domain.CoreManager
import com.wireturn.app.viewmodel.CoreState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/** No native process or network: real manager/observers with intercepted Android dispatch. */
internal object CoreManagerStopAudit {
    suspend fun runControls(context: Context): String {
        check(CoreServiceState.status.value is CoreStatus.Idle)
        val config = AppPreferences(context).clientConfigFlow.first()
        val outcomes = mutableListOf<String>()
        for (succeeds in listOf(true, false)) {
            val starts = AtomicInteger()
            val stops = AtomicInteger()
            val intercepted = object : ContextWrapper(context.applicationContext) {
                override fun startForegroundService(service: Intent): ComponentName? {
                    starts.incrementAndGet()
                    return ComponentName(packageName, CoreService::class.java.name)
                }
                override fun startService(service: Intent): ComponentName? {
                    stops.incrementAndGet()
                    return ComponentName(packageName, CoreService::class.java.name)
                }
            }
            val manager = CoreManager(intercepted)
            val observer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                observer.launch { manager.observeCoreServiceStatus() }
                val attempt = observer.launch { manager.startCore(config) }
                withTimeout(5000) { while (starts.get() == 0) delay(10) }
                if (succeeds) {
                    CoreServiceState.setStatus(CoreStatus.Connecting)
                    withTimeout(5000) { attempt.join() }
                    CoreServiceState.setStatus(CoreStatus.Connected)
                    withTimeout(5000) { manager.coreState.first { it is CoreState.Connected } }
                    check(stops.get() == 0)
                    outcomes += "normal start reached Connected without Stop"
                } else {
                    delay(20_500)
                    check(attempt.isCompleted && stops.get() == 1 && manager.coreState.value is CoreState.Error)
                    outcomes += "genuine startup timeout still reported Error and exactly one Stop"
                }
            } finally {
                observer.cancel();manager.destroy();CoreServiceState.setStatus(CoreStatus.Idle)
            }
        }
        return outcomes.joinToString("; ")
    }

    suspend fun run(context: Context): String {
        check(CoreServiceState.status.value is CoreStatus.Idle)
        val config = AppPreferences(context).clientConfigFlow.first()
        val outcomes = mutableListOf<String>()
        for (throughManager in listOf(true, false)) {
            val starts = AtomicInteger()
            val stops = AtomicInteger()
            val intercepted = object : ContextWrapper(context.applicationContext) {
                override fun startForegroundService(service: Intent): ComponentName? {
                    starts.incrementAndGet()
                    return ComponentName(packageName, CoreService::class.java.name)
                }
                override fun startService(service: Intent): ComponentName? {
                    stops.incrementAndGet()
                    return ComponentName(packageName, CoreService::class.java.name)
                }
            }
            val manager = CoreManager(intercepted)
            val observer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                observer.launch { manager.observeCoreServiceStatus() }
                val attempt = observer.launch { manager.startCore(config) }
                withTimeout(5000) { while (starts.get() == 0) delay(10) }
                withContext(Dispatchers.Main) {
                    if (throughManager) manager.stopCore()
                    CoreServiceState.setStatus(CoreStatus.Stopping)
                }
                delay(100)
                CoreServiceState.setStatus(CoreStatus.Idle)
                delay(20_500)
                val expectedStops = if (throughManager) 1 else 0
                val good = attempt.isCompleted && stops.get() == expectedStops && manager.coreState.value is CoreState.Idle
                outcomes += "managerStop=$throughManager completed=${attempt.isCompleted} stops=${stops.get()}/$expectedStops state=${manager.coreState.value.javaClass.simpleName} passed=$good"
            } finally {
                observer.cancel();manager.destroy();CoreServiceState.setStatus(CoreStatus.Idle)
            }
        }
        check(outcomes.all { it.endsWith("passed=true") }) { outcomes.joinToString("; ") }
        return outcomes.joinToString("; ")
    }
}
