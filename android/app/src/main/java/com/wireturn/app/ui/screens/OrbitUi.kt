@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wireturn.app.ui.screens

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.wireturn.app.BuildConfig
import com.wireturn.app.CoreServiceState
import com.wireturn.app.R
import com.wireturn.app.data.InterfaceStyle
import com.wireturn.app.domain.pollWhileVisible
import com.wireturn.app.ui.activities.*
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.map
import java.util.Locale

@Composable
fun OrbitPowerControl(state: String, status: String, locked: Boolean, onClick: () -> Unit) {
    val session by CoreServiceState.session.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lifecycle, session?.generation, state) {
        elapsed = 0
        val started = session?.startedAtElapsed
        if (started != null && state == "active") {
            pollWhileVisible(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }) {
                elapsed = ((SystemClock.elapsedRealtime() - started) / 1000).coerceAtLeast(0)
            }
        }
    }
    val active = state == "active"
    val failed = state == "error"
    val accent = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val action = stringResource(if (active || state == "loading") R.string.orbit_disconnect else R.string.orbit_connect)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(222.dp).background(accent.copy(alpha = .06f), CircleShape))
            Box(Modifier.size(198.dp).background(accent.copy(alpha = .12f), CircleShape))
            Box(Modifier.size(172.dp).clip(CircleShape)
                .background(Brush.linearGradient(if (failed) listOf(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
                    else if (active || state == "loading") listOf(Color(0xFF8571FA), Color(0xFF5034C8))
                    else listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primaryContainer)))
                .clickable(enabled = !locked, role = Role.Button, onClickLabel = action, onClick = onClick), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state == "loading") CircularProgressIndicator(Modifier.size(43.dp), color = Color.White, strokeWidth = 3.dp)
                    else Icon(painterResource(R.drawable.power_24px), action, Modifier.size(44.dp), tint = if(active) Color.White else accent)
                    Text(stringResource(if (active) R.string.orbit_connected else if (state == "loading") R.string.connecting else R.string.orbit_connect),
                        style = MaterialTheme.typography.labelMedium, color = if(active || state == "loading") Color.White else accent)
                }
            }
        }
        Text(status, style = MaterialTheme.typography.titleLarge, color = if(failed) accent else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 12.dp, end = 12.dp))
        if (active && session != null) Text(String.format(Locale.ROOT,"%02d:%02d:%02d",elapsed/3600,(elapsed/60)%60,elapsed%60),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun OrbitBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.orbit_home to R.drawable.orbit_home_24px, R.string.orbit_servers to R.drawable.ethernet_24px,
                    R.string.orbit_settings to R.drawable.settings_24px).forEachIndexed { index, (label, icon) ->
                    Surface(onClick = { onSelect(index) }, modifier = Modifier.weight(1f).semantics { this.selected = selected == index }, shape = RoundedCornerShape(17.dp),
                        color = if (selected == index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if(selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                        Column(Modifier.heightIn(min = 62.dp).padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painterResource(icon), null, Modifier.size(22.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OrbitSettingsScreen(viewModel: MainViewModel, onServers: () -> Unit, onAllSettings: () -> Unit) {
    val context = LocalContext.current
    var components by rememberSaveable { mutableStateOf(false) }
    fun open(target: Class<out android.app.Activity>) { context.startActivity(Intent(context, target)) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.orbit_settings), fontWeight = FontWeight.SemiBold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        contentWindowInsets = WindowInsets(0,0,0,0)) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OrbitSectionTitle(R.string.orbit_connection)
                OrbitSettingsGroup {
                    OrbitSettingsRow(R.drawable.route_24px, R.string.orbit_routing) {
                        context.startActivity(Intent(context, XraySettingsActivity::class.java).putExtra("EXTRA_PROFILE_ID",viewModel.currentProfileId.value))
                    }
                    OrbitSettingsRow(R.drawable.grid_view_24px, R.string.orbit_apps, R.string.orbit_apps_note) { open(AppExceptionsActivity::class.java) }
                    OrbitSettingsRow(R.drawable.note_add_24px, R.string.orbit_subscriptions, onClick = onServers)
                    OrbitSettingsRow(R.drawable.lan_24px, R.string.orbit_advanced) { open(ConnectionSettingsActivity::class.java) }
                }
                OrbitSectionTitle(R.string.orbit_application)
                OrbitSettingsGroup {
                    OrbitSettingsRow(R.drawable.settings_24px, R.string.orbit_appearance) {
                        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("APPEARANCE_ONLY", true))
                    }
                    OrbitSettingsRow(R.drawable.terminal_24px, R.string.logs_title) { open(LogsActivity::class.java) }
                    OrbitSettingsRow(R.drawable.info_24px, R.string.orbit_components) { components = true }
                    OrbitSettingsRow(R.drawable.more_vert_24px, R.string.orbit_more, onClick = onAllSettings)
                }
                Text(BuildConfig.VERSION_NAME, Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (components) ModalBottomSheet(onDismissRequest = { components = false }) { CoreVersionsContent() }
}

@Composable
fun OrbitSectionTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun OrbitSettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(content = content)
    }
}

@Composable
private fun OrbitSettingsRow(icon: Int, title: Int, subtitle: Int? = null, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 70.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                if(subtitle != null) Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
