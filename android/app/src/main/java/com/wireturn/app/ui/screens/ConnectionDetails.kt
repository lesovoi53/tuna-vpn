@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wireturn.app.ui.screens

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.wireturn.app.domain.ConnectionFact
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.wireturn.app.CoreServiceState
import com.wireturn.app.R
import com.wireturn.app.data.*
import com.wireturn.app.domain.ConnectionFacts
import com.wireturn.app.domain.ExitObservationState
import com.wireturn.app.domain.pollWhileVisible
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ExitAddressCard(state: ExitObservationState, privacy: Boolean, refresh: () -> Unit) {
    val clipboard = LocalClipboard.current
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.cd_copy_ip)
    val context = LocalContext.current
    val value = state.observation
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cd_exit), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (value != null && !privacy) IconButton(onClick = { scope.launch {
                    clipboard.setClipEntry(ClipData.newPlainText(copyLabel, value.ip).toClipEntry())
                    android.widget.Toast.makeText(context, "IP скопирован", android.widget.Toast.LENGTH_SHORT).show()
                } }) { Icon(painterResource(R.drawable.content_copy_24px), stringResource(R.string.cd_copy_ip)) }
                IconButton(onClick = refresh, enabled = !state.loading) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.refresh_24px), stringResource(R.string.cd_refresh_ip))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (privacy && value != null) "••••••••" else value?.ip ?: if(state.loading) stringResource(R.string.cd_detecting) else stringResource(R.string.cd_unknown_exit),
                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
            }
            if (value != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!privacy) value.country?.let { Text(Locale.Builder().setRegion(it).build().getDisplayCountry(displayLocale)) }
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(when(value.warp) { true -> "WARP"; false -> stringResource(R.string.cd_no_warp); null -> stringResource(R.string.cd_warp_unknown) }, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(stringResource(R.string.cd_checked_at, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, displayLocale).format(java.util.Date(value.checkedAt))), style = MaterialTheme.typography.bodySmall)
                if (state.unavailable) Text("Обновить не удалось · показан последний результат", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FactCard(title: String, rows: List<ConnectionFact>, privacy: Boolean = false) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            rows.forEachIndexed { index, row ->
                val label = row.labelText ?: stringResource(row.label)
                val value = row.valueResource?.let { stringResource(it) } ?: row.value
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val text = if(row.masked(privacy)) "••••••••" else value.ifBlank { stringResource(R.string.cd_not_set) }
                    if (maxWidth < 310.dp || LocalDensity.current.fontScale > 1.2f || text.length > 42) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, Modifier.weight(0.43f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text, Modifier.weight(0.57f), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionDetailsDialog(session: CoreServiceState.RunningSession?, config: ClientConfig,
    settings: XraySettings, routingEnabled: Boolean, routingSource: String, vpn: VpnSettings,
    exit: ExitObservationState, refreshExit: () -> Unit, transfer: MainViewModel.TransferResult?,
    ping: MainViewModel.PingResult?, privacy: Boolean, onDismiss: () -> Unit,
    testResults: @Composable () -> Unit = {}, localProxies: @Composable () -> Unit) {
    val facts = remember(config.kernelConfig) { ConnectionFacts.from(config.kernelConfig) }
    val restart by CoreServiceState.restartAttempt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val versions by produceState<Map<String, String>>(emptyMap()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.assets.open("native/components.json").bufferedReader().use { reader ->
                com.google.gson.JsonParser.parseReader(reader).asJsonArray.associate { element ->
                    val entry = element.asJsonObject
                    entry["name"].asString to (entry["version"].asString + if(entry["tunaFork"].asBoolean) " · TUNA fork" else "")
                }
            } }.getOrDefault(emptyMap())
        }
    }
    var elapsed by remember(session?.generation) { mutableLongStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, session?.generation) {
        val started = session?.startedAtElapsed ?: return@LaunchedEffect
        pollWhileVisible(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }) {
            elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0) / 1000
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = { TopAppBar(title = { Text(stringResource(R.string.cd_title), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.cd_back)) } }) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FactCard(stringResource(R.string.cd_current), listOf(
                    ConnectionFact(R.string.cd_profile, session?.profileName ?: stringResource(R.string.cd_connection), sensitive = true),
                    ConnectionFact(R.string.cd_protocol, facts.protocol),
                    ConnectionFact(R.string.cd_duration, "%02d:%02d:%02d".format(elapsed/3600, elapsed/60%60, elapsed%60))), privacy)
                val dnsPlan = config.masterDnsPoolPlan
                if (dnsPlan != null) MasterDnsPlanDetails(dnsPlan, privacy)
                else FactCard(stringResource(R.string.cd_server), listOf(ConnectionFact(R.string.cd_address, facts.server,
                    sensitive = true)), privacy)
                ExitAddressCard(exit, privacy, refreshExit)
                Text(stringResource(R.string.cd_trace_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dnsPlan == null) FactCard(stringResource(R.string.cd_parameters), facts.rows, privacy)
                val engine = (config.kernelConfig as? KernelConfig.Vpn)?.config?.engineLabel ?: facts.protocol
                versions[engine]?.let { FactCard(stringResource(R.string.cd_connection_core), listOf(ConnectionFact(R.string.cd_core, it, labelText = engine))) }
                when(val kernel = config.kernelConfig) {
                    is KernelConfig.Webdav -> {
                        FactCard(stringResource(R.string.cd_storage), (listOf(ConnectionFact(R.string.cd_primary, ConnectionFacts.endpoint(kernel.config.webdav), sensitive = true)) + kernel.config.backends.mapIndexed { i, b ->
                            ConnectionFact(R.string.cd_storage, ConnectionFacts.endpoint(b.url), sensitive = true,
                                labelText = b.label.ifBlank { resources.getString(R.string.cd_storage_number, i+2) }) }), privacy)
                        Text(stringResource(R.string.cd_storage_note), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> WorkerStatusCard(config.kernelConfig)
                }
                RoutingSettingsGroup(stringResource(R.string.cd_routing_dns), routingSource) {
                    val dnsDisplay = remember(settings, config.dns, config.kernelConfig) {
                        com.wireturn.app.domain.EffectiveDnsDisplay.resolve(settings, config.dns,
                            (config.kernelConfig as? KernelConfig.Vpn)?.config?.isRawXray == true)
                    }
                    FactCard(stringResource(R.string.cd_applied_settings), listOf(
                        ConnectionFact(R.string.cd_routing, routingSource, valueResource = if(routingEnabled) null else R.string.cd_disabled)) +
                        dnsDisplay.rows + listOf(
                        ConnectionFact(R.string.cd_apps, if(!vpn.filteringEnabled) stringResource(R.string.cd_all_apps) else
                            stringResource(if(vpn.bypassMode) R.string.cd_excluded_apps else R.string.cd_selected_apps, vpn.excludedApps.size))), privacy)
                }
                RoutingSettingsGroup(stringResource(R.string.cd_traffic_recovery), stringResource(R.string.cd_session_stats)) {
                    fun bytes(value: Long) = resources.getString(R.string.cd_mib, value / 1048576.0)
                    FactCard(stringResource(R.string.cd_statistics), listOf(
                        ConnectionFact(R.string.cd_received, transfer?.let { bytes(it.rx) }.orEmpty(), valueResource = if(transfer == null) R.string.cd_no_data else null),
                        ConnectionFact(R.string.cd_sent, transfer?.let { bytes(it.tx) }.orEmpty(), valueResource = if(transfer == null) R.string.cd_no_data else null),
                        ConnectionFact(R.string.cd_speed, transfer?.let { stringResource(R.string.cd_speed_value, it.rxSpeed/1024, it.txSpeed/1024) }.orEmpty(), valueResource = if(transfer == null) R.string.cd_no_data else null),
                        ConnectionFact(R.string.cd_latency, (ping as? MainViewModel.PingResult.Success)?.let { stringResource(R.string.cd_ms, it.ms) }.orEmpty(), valueResource = if(ping !is MainViewModel.PingResult.Success) R.string.cd_not_measured else null),
                        ConnectionFact(R.string.cd_recovery, restart?.let { stringResource(R.string.cd_attempt, it.attempt, it.max) }.orEmpty(), valueResource = if(restart == null) R.string.cd_no_retry else null)))
                }
                testResults()
                localProxies()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
