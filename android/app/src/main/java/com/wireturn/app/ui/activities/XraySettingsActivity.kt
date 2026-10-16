package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.XraySettingsScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class XraySettingsActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        setContent {
            val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
            if (!isInitialized) return@setContent

            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            var saveError by remember { mutableStateOf<String?>(null) }

            val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
            val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()
            val currentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
            val targetProfile = profiles.find { it.id == (intent.getStringExtra("EXTRA_PROFILE_ID") ?: currentId) }
            val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
            val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
            val geoAssetsState by viewModel.geoAssetsState.collectAsStateWithLifecycle()
            val geoAssetsProgress by viewModel.geoAssetsProgress.collectAsStateWithLifecycle()
            val geoAssetsInstalledTick by viewModel.geoAssetsInstalledTick.collectAsStateWithLifecycle()
            // Reading the tick alone doesn't help unless something derived from it is actually
            // captured by the WireturnTheme content lambda below - otherwise Compose sees that
            // lambda's captures as unchanged and skips re-invoking it, so geoAssetsInstalled()/
            // geoAssetsInstalledAt() never get re-read after a download or delete completes.
            val geoAssetsInstalled = geoAssetsInstalledTick.let { viewModel.geoAssetsInstalled() }
            val geoAssetsInstalledAt = geoAssetsInstalledTick.let { viewModel.geoAssetsInstalledAt() }

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                if (saving || saveError != null) AlertDialog(
                    onDismissRequest = { if (!saving) saveError = null },
                    title = { Text(if (saving) "Проверка правил" else "Не удалось сохранить") },
                    text = { Text(saveError ?: "Проверяем маршрутизацию и DNS перед сохранением…") },
                    confirmButton = { if (!saving) TextButton(onClick = { saveError = null }) { Text("Понятно") } },
                )
                com.wireturn.app.ui.theme.CompactSettingsTheme {
                XraySettingsScreen(
                    initialXraySettings = xraySettings,
                    privacyMode = privacyMode,
                    subscriptions = subscriptions,
                    activeSubscription = subscriptions.find { it.id == targetProfile?.subscriptionId },
                    routingEnabled = xrayConfig.routingEnabled,
                    onEnableRouting = viewModel::enableRouting,
                    onDisableRouting = viewModel::disableRouting,
                    profileName = targetProfile?.name,
                    geoAssetsState = geoAssetsState,
                    geoAssetsProgress = geoAssetsProgress,
                    geoAssetsInstalled = geoAssetsInstalled,
                    geoAssetsInstalledAt = geoAssetsInstalledAt,
                    onDownloadGeoAssets = { viewModel.downloadGeoAssets(it) },
                    onCancelGeoAssetsDownload = { viewModel.cancelGeoAssetsDownload() },
                    onDeleteGeoAssets = { viewModel.deleteGeoAssets() },
                    onBack = { finish() },
                    onSave = { xray ->
                        if (!saving) scope.launch {
                            saving = true
                            try {
                                viewModel.saveValidatedXraySettings(xray)
                                finish()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { saveError = e.message ?: "Проверьте правила и DNS" }
                            finally { saving = false }
                        }
                    }
                )
                }
            }
        }
    }
}
