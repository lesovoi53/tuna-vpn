package com.wireturn.app

import android.app.Application
import android.app.Instrumentation
import androidx.lifecycle.ViewModelStore
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** A stale UI row after a real DataStore update/removal. Never starts a tunnel. */
internal object ProfileSelectionRaceAudit {
    suspend fun run(instrumentation: Instrumentation): String {
        check(!CoreServiceState.isRunning.value)
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = AppPreferences(app)
        val before = prefs.profilesFlow.first()
        val originalId = prefs.currentProfileIdFlow.first()
        val original = before.single { it.id == originalId }
        val originalGroup = prefs.activeConnectionGroupFlow.first()
        val old = Profile("qa-rc30-selection-race", "QA selection stale", KernelConfig.Vpn(VpnConfig(
            "vless://11111111-2222-4333-8444-555555555555@192.0.2.4:443?security=tls", "xray")))
        val updated = old.copy(name = "QA selection refreshed", kernelConfig = KernelConfig.Vpn(VpnConfig(
            "vless://11111111-2222-4333-8444-555555555555@192.0.2.4:8443?security=tls", "xray")))
        check(before.none { it.id == old.id })
        val models = ViewModelStore()
        lateinit var model: MainViewModel
        try {
            prefs.editProfiles(ProfileListEdit.Append(old))
            instrumentation.runOnMainSync { model = MainViewModel(app); models.put("qa-selection", model) }
            withTimeout(5000) { model.profiles.first { profiles -> profiles.any { it.id == old.id } } }
            prefs.editProfiles(ProfileListEdit.Replace(updated))
            val selected = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.selectProfileAndRestart(old.id, old) { selected.complete(Unit) } }
            withTimeout(5000) { selected.await() }
            check(prefs.currentProfileIdFlow.first() == old.id)
            check((prefs.clientConfigFlow.first().kernelConfig as KernelConfig.Vpn).config.uri.contains(":8443?"))
            check(!CoreServiceState.isRunning.value)

            prefs.saveFullProfile(original.id, original)
            prefs.editProfiles(ProfileListEdit.Remove(setOf(old.id)))
            val removed = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.selectProfileAndRestart(old.id, old) { removed.complete(Unit) } }
            withTimeout(5000) { removed.await() }
            check(prefs.currentProfileIdFlow.first() == originalId)
            check(!CoreServiceState.isRunning.value)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            prefs.editProfiles(ProfileListEdit.Remove(setOf(old.id)))
            prefs.saveFullProfile(originalId, original)
            prefs.setActiveConnectionGroup(originalGroup)
        }
        check(before.associateBy { it.id } == prefs.profilesFlow.first().associateBy { it.id })
        return "stale UI row activated refreshed port; removed row ignored; stopped VPN and all original profiles preserved"
    }
}
