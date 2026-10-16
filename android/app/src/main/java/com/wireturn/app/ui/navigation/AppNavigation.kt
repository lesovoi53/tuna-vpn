@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.data.InterfaceStyle
import com.wireturn.app.ui.screens.OrbitBottomBar
import com.wireturn.app.ui.screens.OrbitSettingsScreen
import com.wireturn.app.ui.screens.ProfilesDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wireturn.app.ui.CoreTriggerController
import com.wireturn.app.ui.TileVpnConsentBus
import com.wireturn.app.ui.activities.AppExceptionsActivity
import com.wireturn.app.ui.screens.HomeScreen
import com.wireturn.app.viewmodel.MainViewModel

@Composable
fun AppNavigation(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val style by viewModel.interfaceStyle.collectAsStateWithLifecycle()
    val privacy by viewModel.privacyMode.collectAsStateWithLifecycle()
    val screenStates = rememberSaveableStateHolder()
    val orbit = style == InterfaceStyle.ORBIT
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val linkRequest by com.wireturn.app.ui.DeepLinkBus.navigationRequest.collectAsStateWithLifecycle()
    LaunchedEffect(linkRequest) { if (com.wireturn.app.ui.DeepLinkBus.consumeNavigation(linkRequest)) tab = 0 }
    LaunchedEffect(orbit) { if (!orbit) tab = 0 }
    BackHandler(orbit && tab != 0) { tab = 0 }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            CoreTriggerController(viewModel = viewModel) { onToggle, onStart ->
                LaunchedEffect(onStart) {
                    TileVpnConsentBus.pending.collect { onStart() }
                }
                Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                CompositionLocalProvider(com.wireturn.app.ui.screens.LocalProfilePrivacy provides privacy) {
                when {
                orbit && tab == 1 -> Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                    screenStates.SaveableStateProvider("profiles") {
                        ProfilesDialog(viewModel, onDismiss = { tab = 0 }, embedded = true)
                    }
                }
                orbit && tab == 2 -> OrbitSettingsScreen(viewModel,
                    onServers = { tab = 1 },
                    onAllSettings = { context.startActivity(Intent(context, com.wireturn.app.ui.activities.SettingsActivity::class.java)) })
                else -> HomeScreen(
                    viewModel = viewModel,
                    onNavigateToExclusions = { 
                        context.startActivity(Intent(context, AppExceptionsActivity::class.java))
                    },
                    onNavigateToXrayConfig = {
                        val intent = Intent(context, com.wireturn.app.ui.activities.XraySettingsActivity::class.java)
                        intent.putExtra("EXTRA_PROFILE_ID", viewModel.currentProfileId.value)
                        context.startActivity(intent)
                    },
                    onNavigateToConnectionSettings = {
                        context.startActivity(Intent(context, com.wireturn.app.ui.activities.ConnectionSettingsActivity::class.java))
                    },
                    onNavigateToSettings = {
                        if (orbit) tab = 2 else context.startActivity(Intent(context, com.wireturn.app.ui.activities.SettingsActivity::class.java))
                    },
                    onNavigateToLogs = {
                        context.startActivity(Intent(context, com.wireturn.app.ui.activities.LogsActivity::class.java))
                    },
                    onToggleProxy = onToggle,
                    onOpenProfiles = if (orbit) ({ tab = 1 }) else null
                )
                }
                }
                }
                if (orbit) OrbitBottomBar(tab) { tab = it }
                }
            }
        }
    }
}
