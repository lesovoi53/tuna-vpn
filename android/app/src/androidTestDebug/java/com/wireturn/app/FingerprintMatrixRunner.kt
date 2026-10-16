package com.wireturn.app

import android.app.Instrumentation
import android.os.Bundle
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.NativeOptions
import com.wireturn.app.data.kernel.VpnOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Debug test APK only: activates a QA copy without editing the stored profile list. */
class FingerprintMatrixRunner : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); super.onCreate(arguments); start() }
    override fun onStart() {
        try { runAudit() }
        catch (failure: Throwable) {
            val writer = java.io.PrintWriter(java.io.File(targetContext.filesDir, "qa-instrumentation-error.txt"))
            failure.printStackTrace(writer)
            writer.close()
            finish(1, Bundle().apply { putString("error", failure.javaClass.name) })
        }
    }
    private fun runAudit() {
        val result = Bundle()
        if (args.containsKey("serverAudit")) {
            startActivitySync(android.content.Intent(targetContext, com.wireturn.app.ui.activities.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            result.putString("result", ServerSshAudit.run(targetContext, args))
            finish(0, result)
            return
        }
        if (args.getString("serverVault") == "true") {
            result.putString("result", ServerVaultAudit.run(targetContext))
            finish(0, result)
            return
        }
        if (args.getString("localVolgaPair") == "true") {
            startActivitySync(android.content.Intent(targetContext, com.wireturn.app.ui.activities.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            result.putString("result", runBlocking { VolgaPairAudit.run(targetContext) })
            finish(0, result)
            return
        }
        if (args.getString("localSnellResolution") == "true") {
            startActivitySync(android.content.Intent(targetContext, com.wireturn.app.ui.activities.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            result.putString("result", runBlocking { SnellResolutionAudit.run(targetContext) })
            finish(0, result)
            return
        }
        if (args.getString("localCoreManagerStop") == "true" || args.getString("localCoreManagerControls") == "true") {
            // HONOR may freeze a headless instrumented process during timed audits.
            startActivitySync(android.content.Intent(targetContext, com.wireturn.app.ui.activities.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            result.putString("result", runBlocking {
                if (args.getString("localCoreManagerControls") == "true") CoreManagerStopAudit.runControls(targetContext)
                else CoreManagerStopAudit.run(targetContext)
            })
            finish(0, result)
            return
        }
        if (args.getString("localProfileRefreshStop") == "true") {
            result.putString("result", runBlocking { ProfileRefreshStopAudit.run(targetContext) })
            finish(0, result)
            return
        }
        if (args.getString("localProfileSelectionRace") == "true") {
            result.putString("result", runBlocking { ProfileSelectionRaceAudit.run(this@FingerprintMatrixRunner) })
            finish(0, result)
            return
        }
        if (args.getString("localHeadlessImport") == "true") {
            result.putString("result", runBlocking { ProfileManagerHeadlessAudit.run(targetContext) })
            finish(0, result)
            return
        }
        if (args.getString("localViewModelLifetime") == "true") {
            result.putString("result", runBlocking { ViewModelLifetimeAudit.run(this@FingerprintMatrixRunner) })
            finish(0, result)
            return
        }
        if (args.getString("localSubscriptionDownload") == "true") {
            result.putString("result", runBlocking { SubscriptionDownloadAudit.run() })
            finish(0, result)
            return
        }
        if (args.getString("localDiagnosticHttp") == "true") {
            result.putString("result", runBlocking { DiagnosticHttpAudit.run() })
            finish(0, result)
            return
        }
        // Instrumentation alone has no visible activity; OEM background freezing invalidates
        // timing tests and can suspend native children. Keep the app visible during acceptance.
        if (args.containsKey("releaseAction") || args.getString("matrix") == "true") {
            startActivitySync(android.content.Intent(targetContext, com.wireturn.app.ui.activities.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (args.containsKey("releaseAction")) {
            try {
                result.putString("result", runBlocking { ReleaseAcceptanceAudit.run(targetContext, args) })
                finish(0, result)
            } catch (failure: Exception) {
                result.putString("error", failure.javaClass.simpleName)
                finish(1, result)
            }
            return
        }
        if (args.getString("matrix") == "true") {
            try {
                runBlocking { ProfileMatrixAudit.run(targetContext, args) { sendStatus(0, it) } }
                result.putString("result", "matrix_complete")
                finish(0, result)
            } catch (failure: Exception) {
                result.putString("error", failure.javaClass.simpleName)
                finish(1, result)
            }
            return
        }
        try {
            runBlocking {
                val prefs = AppPreferences(targetContext)
                val name = args.getString("profile") ?: error("Missing profile")
                require(name.startsWith("QA143 ") || name == "Профиль #2")
                val selected = prefs.profilesFlow.first().single { it.name == name }
                if (args.getString("inspect") == "true") {
                    val vpn = (selected.kernelConfig as KernelConfig.Vpn).config
                    val fp = com.wireturn.app.data.kernel.VlessFingerprintChoices.effective(vpn)
                    require(fp in com.wireturn.app.data.kernel.VlessFingerprintChoices.forEngine(
                        com.wireturn.app.data.kernel.CoreCapabilities.resolve(vpn)))
                    result.putString("fingerprint", fp)
                } else if (args.getString("restore") == "true") prefs.saveFullProfile(selected.id, selected)
                else {
                    val vpn = (selected.kernelConfig as KernelConfig.Vpn).config
                    val engine = args.getString("engine") ?: error("Missing engine")
                    require(engine in listOf("sing-box", "xray"))
                    val fp = args.getString("fp") ?: error("Missing fingerprint")
                    require(fp in listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"))
                    val uri = java.net.URI(vpn.uri)
                    val entries = uri.rawQuery.split('&').filter { it.substringBefore('=') != "fp" } + "fp=$fp"
                    val updatedUri = vpn.uri.substringBefore('?') + "?" + entries.joinToString("&") + (uri.rawFragment?.let { "#$it" } ?: "")
                    var options = vpn.options ?: VpnOptions()
                    val path = if (engine == "sing-box") "tls.utls.fingerprint" else "streamSettings.realitySettings.fingerprint"
                    options = options.withEngine(engine, NativeOptions.put(options.forEngine(engine), path, null))
                    val changed = vpn.copy(uri = updatedUri, engine = engine, options = options)
                    changed.validate()
                    prefs.saveFullProfile(selected.id, selected.copy(kernelConfig = KernelConfig.Vpn(changed)))
                }
            }
            result.putString("result", "activated"); finish(0, result)
        } catch (error: Exception) {
            result.putString("error", error.javaClass.simpleName); finish(1, result)
        }
    }
}
