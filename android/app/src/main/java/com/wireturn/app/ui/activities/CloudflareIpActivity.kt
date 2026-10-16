package com.wireturn.app.ui.activities

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.domain.*
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.CloudflareViewModel

class CloudflareIpActivity : ComponentActivity() {
    private val model: CloudflareViewModel by viewModels()
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra("EXTRA_PROFILE_ID") ?: return finish()
        model.load(id)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val theme by model.prefs.themeModeFlow.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val dynamic by model.prefs.dynamicThemeFlow.collectAsStateWithLifecycle(false)
            var count by rememberSaveable { mutableStateOf("128") }
            var parallel by rememberSaveable { mutableStateOf("3") }
            var timeout by rememberSaveable { mutableStateOf("5") }
            var samples by rememberSaveable { mutableStateOf("3") }
            var quick by rememberSaveable { mutableStateOf(true) }
            var target by rememberSaveable { mutableStateOf("3") }
            var tcpParallel by rememberSaveable { mutableStateOf("16") }
            var budget by rememberSaveable { mutableStateOf("90") }
            var custom by rememberSaveable { mutableStateOf("") }
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            var advanced by rememberSaveable { mutableStateOf(false) }
            var failuresOpen by rememberSaveable { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            val settings = runCatching { CfSettings(count.toInt(), parallel.toInt(), timeout.toInt(), samples.toInt(),
                quick, if (quick) target.toInt() else 3, tcpParallel.toInt(), budget.toInt()) }.getOrNull()
            val editable = !state.busy && !state.applying
            val working = state.choices.sortedWith(CfResult.order)
            val failed = state.results.filterNot { it.usable }
            LaunchedEffect(editable) { if (!editable) menu = false }
            DisposableEffect(state.busy) {
                if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            WireturnTheme(themeMode = theme, dynamicColor = dynamic) {
                Scaffold(topBar = { AppTopAppBar(stringResource(R.string.cf_title), onBack = { finish() }) }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text(state.profile?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                            val config = (state.profile?.kernelConfig as? KernelConfig.Vpn)?.config
                            val description = remember(config) {
                                val query = runCatching { CloudflareProfile.query(java.net.URI(config?.uri.orEmpty())) }.getOrDefault(emptyMap())
                                listOfNotNull(config?.protocol?.uppercase(), query["type"]?.uppercase(), query["security"]?.uppercase())
                            }
                            Text((description + stringResource(if (!state.networkAvailable) R.string.cf_network_offline
                                else if (state.wifi) R.string.cf_network_wifi else R.string.cf_network_other))
                                .filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            CfSection {
                                Text(stringResource(R.string.cf_address), style = MaterialTheme.typography.titleMedium)
                                Box {
                                    OutlinedButton(onClick = { menu = true }, enabled = editable && (working.isNotEmpty() || state.selectedIp != null),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                        Text(state.selectedIp ?: stringResource(R.string.cf_original, state.originalHost), Modifier.weight(1f))
                                        Text("⌄", Modifier.padding(start = 12.dp))
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.cf_original, state.originalHost)) },
                                            onClick = { menu = false; model.select(null) })
                                        working.forEach { result ->
                                            DropdownMenuItem(text = { Text("${result.ip} · " + stringResource(R.string.cf_ms, result.medianMs)) },
                                                onClick = { menu = false; model.select(result.ip) })
                                        }
                                    }
                                }
                                if (state.selectedIp != null) Text(stringResource(R.string.cf_original, state.originalHost),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.profile?.subscriptionId != null) {
                                    HorizontalDivider()
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.keep_subscription_address), Modifier.weight(1f))
                                        Switch(state.profile?.keepSubscriptionAddress == true, model::setKeepAddress, enabled = editable)
                                    }
                                    Text(stringResource(R.string.cf_lock_hint), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(stringResource(R.string.cf_address_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        state.unavailable?.let { reason -> item {
                            Text(stringResource(when (reason) {
                                CfUnavailable.PROTOCOL -> R.string.cf_unavailable_protocol
                                CfUnavailable.TRANSPORT -> R.string.cf_unavailable_transport
                                CfUnavailable.HTTP3 -> R.string.cf_unavailable_http3
                                CfUnavailable.TLS -> R.string.cf_unavailable_tls
                                CfUnavailable.PORT -> R.string.cf_unavailable_port
                                CfUnavailable.HOST -> R.string.cf_unavailable_host
                                CfUnavailable.OVERRIDE -> R.string.cf_unavailable_override
                                CfUnavailable.DOWNLOAD -> R.string.cf_unavailable_download
                                CfUnavailable.INVALID -> R.string.cf_unavailable_invalid
                            }), color = MaterialTheme.colorScheme.error)
                        } }
                        item {
                            CfSection {
                                Text(stringResource(R.string.cf_search_mode), style = MaterialTheme.typography.titleMedium)
                                CfModePicker(quick, editable) { value ->
                                    quick = value
                                    if (budget in setOf("90", "600")) budget = if (value) "90" else "600"
                                }
                                Text(stringResource(if (quick) R.string.cf_quick_note else R.string.cf_full_note),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            OutlinedCard(onClick = { settingsOpen = !settingsOpen }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.cf_settings), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.cf_settings_summary, count, budget), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(if (settingsOpen) "−" else "+", Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                        if (settingsOpen) item {
                            CfSection {
                                CfNumber(R.string.cf_count, count, { count = it }, editable)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("256", "1000", "5000").forEach { value ->
                                        OutlinedButton(onClick = { count = value }, enabled = editable, modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text(value) }
                                    }
                                }
                                if (quick) CfNumber(R.string.cf_target, target, { target = it }, editable)
                                CfNumber(R.string.cf_budget, budget, { budget = it }, editable)
                                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.cf_advanced) + if (advanced) " −" else " +")
                                }
                                if (advanced) {
                                    CfNumber(R.string.cf_parallel, parallel, { parallel = it }, editable)
                                    CfNumber(R.string.cf_tcp_parallel, tcpParallel, { tcpParallel = it }, editable)
                                    CfNumber(R.string.cf_timeout, timeout, { timeout = it }, editable)
                                    CfNumber(R.string.cf_samples, samples, { samples = it }, editable)
                                    OutlinedTextField(custom, { custom = it }, label = { Text(stringResource(R.string.cf_custom)) },
                                        enabled = editable, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                                }
                                Text(stringResource(R.string.cf_large_scan_note), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            if (settings == null) Text(stringResource(R.string.cf_bad_settings), color = MaterialTheme.colorScheme.error)
                            Button(onClick = { if (state.busy) model.stop() else settings?.let { model.start(it, custom) } },
                                enabled = !state.stopping && !state.applying && (state.busy ||
                                    (state.networkAvailable && state.profile != null && state.unavailable == null && settings != null)),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(stringResource(if (state.stopping) R.string.cf_stopping else if (state.busy) R.string.cf_stop
                                    else if (state.total > 0) R.string.cf_repeat else R.string.cf_start))
                            }
                        }
                        if (state.busy || state.total > 0 || state.message != null) item {
                            CfSection {
                                state.message?.let { message -> Text(stringResource(when (message) {
                                    "network_changed" -> R.string.cf_network_changed; "profile_changed" -> R.string.cf_profile_changed
                                    "missing" -> R.string.cf_missing; "no_network" -> R.string.cf_no_network
                                    "bad_ips" -> R.string.cf_bad_ips; "not_cf" -> R.string.cf_not_cf; "no_ipv6" -> R.string.cf_no_ipv6
                                    "none" -> R.string.cf_none; "done" -> R.string.cf_done; "stopped" -> R.string.cf_stopped
                                    "enough" -> R.string.cf_enough; "budget" -> R.string.cf_budget_reached
                                    "selected" -> R.string.cf_selected; else -> R.string.cf_failed
                                })) }
                                if (state.busy) LinearProgressIndicator(progress = {
                                    val active = state.activeSettings
                                    (if (active?.quick == true) state.confirmed.toFloat() / active.target
                                     else state.completed.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                                }, modifier = Modifier.fillMaxWidth())
                                if (state.total > 0) {
                                    Text(stringResource(R.string.cf_pipeline_progress, state.tcpChecked, state.total, state.profileChecked, state.confirmed),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.cf_elapsed, state.elapsedSeconds), style = MaterialTheme.typography.bodySmall)
                                }
                                if (state.busy && state.phase == "stability") Text(stringResource(R.string.cf_stability), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            Text(stringResource(R.string.cf_working, working.size), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.cf_results_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(working, key = { "working:" + it.ip }) { result ->
                            val selected = state.selectedIp == result.ip
                            OutlinedCard(onClick = { model.select(result.ip) }, enabled = editable, modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RadioButton(selected, null, enabled = editable)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(result.ip, style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.cf_success, result.success, result.attempts), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(stringResource(R.string.cf_ms, result.medianMs), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        if (failed.isNotEmpty()) item {
                            TextButton(onClick = { failuresOpen = !failuresOpen }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.cf_failure_details) + if (failuresOpen) " −" else " +")
                            }
                            if (failuresOpen) CfSection {
                                failed.forEach { result ->
                                    Text(result.ip + " · " + stringResource(if (result.failedStage == "TCP") R.string.cf_tcp_failed else R.string.cf_https_failed),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onStop() {
        // A rotation keeps the ViewModel and its scan; leaving the screen cancels it.
        if (!isChangingConfigurations) model.stop()
        super.onStop()
    }
}

@Composable
private fun CfSection(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun CfNumber(label: Int, value: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CfModePicker(quick: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    @Composable fun Choice(value: Boolean, modifier: Modifier) {
        FilledTonalButton(onClick = { change(value) }, enabled = enabled,
            modifier = modifier.heightIn(min = 52.dp).semantics { selected = quick == value },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            colors = if (quick == value) ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
            else ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Text(stringResource(if (value) R.string.cf_mode_quick else R.string.cf_mode_full))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 260.dp || (maxWidth < 400.dp && LocalDensity.current.fontScale > 1.25f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(true, Modifier.fillMaxWidth()); Choice(false, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(true, Modifier.weight(1f)); Choice(false, Modifier.weight(1f))
            }
        }
    }
}
