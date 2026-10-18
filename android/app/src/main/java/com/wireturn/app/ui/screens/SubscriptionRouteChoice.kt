package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Subscription
import com.wireturn.app.domain.ProfileRouting
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SubscriptionRouteChoice(subscription: Subscription?, editable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Источник правил", style = MaterialTheme.typography.titleMedium)
            if (subscription == null) Text("Правила приложения · сервер вне подписок") else {
                val enabled = ProfileRouting.enabled(subscription)
                Text(subscription.name)
                com.wireturn.app.ui.screens.kernel.VkChoice("Маршрутизация", if (enabled) "subscription" else "common",
                    listOf("common" to "Правила приложения", "subscription" to "Правила подписки"),
                    enabled = editable && !busy && (enabled || !subscription.routingSource.isNullOrBlank())) { choice ->
                    scope.launch {
                        busy = true; error = ""
                        try { AppPreferences(context).setSubscriptionRouting(subscription.id, choice == "subscription") }
                        catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = e.message ?: "Не удалось применить правила"
                        } finally { busy = false }
                    }
                }
                Text("Выбор действует только для серверов этой подписки и сохраняется сразу.", style = MaterialTheme.typography.bodySmall)
                if (subscription.routingSource.isNullOrBlank()) Text("Подписка не содержит правил")
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Подготовка правил и баз…") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun RoutingUpdateSettings(editable: Boolean) {
    val context = LocalContext.current
    val prefs = remember { com.wireturn.app.domain.RoutingUpdates.preferences(context) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val hours = tick.let { com.wireturn.app.domain.RoutingUpdates.intervalHours(context) }
    var interval by remember(hours) { mutableStateOf(hours.toString()) }
    val busy = tick.let { prefs.getBoolean("running", false) }
    val error = tick.let { prefs.getString("error", "").orEmpty() }
    val last = tick.let { prefs.getLong("last_success", 0) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обновление баз маршрутизации", style = MaterialTheme.typography.titleMedium)
            Text("GeoIP, GeoSite и необходимые базы подписок. Первый запуск — загрузка полного базового комплекта.")
            OutlinedTextField(interval, { interval = it }, label = { Text("Интервал, часы (1–720)") },
                enabled = editable, isError = interval.toIntOrNull() !in 1..720,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            TextButton(enabled = editable && interval.toIntOrNull() in 1..720 && interval != hours.toString(), onClick = {
                com.wireturn.app.domain.RoutingUpdates.schedule(context, interval.toInt())
            }) { Text("Сохранить интервал") }
            Text(if (last == 0L) "Полное обновление ещё не выполнено" else "Обновлено: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last))}")
            Text("Фоновое обновление может откладываться Android. При запуске проверяется срок последнего обновления.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text("Часть списков пока недоступна. Сохранённые правила остаются; загрузка повторится автоматически.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = editable && !busy, onClick = { scope.launch { com.wireturn.app.domain.RoutingUpdates.run(context, true) } }) { Text("Обновить сейчас") }
        }
    }
}

@Composable
fun AppliedRoutingSummary(privacyMode: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val coreSession by com.wireturn.app.CoreServiceState.session.collectAsStateWithLifecycle()
    val expectedFlow = remember(prefs) { prefs.effectiveXraySettingsFlow.map<com.wireturn.app.data.XraySettings, com.wireturn.app.data.XraySettings?> { it }.catch { emit(null) } }
    val expected by expectedFlow.collectAsStateWithLifecycle(initialValue = null)
    val session by com.wireturn.app.XrayServiceState.session.collectAsStateWithLifecycle()
    val state by com.wireturn.app.XrayServiceState.state.collectAsStateWithLifecycle()
    val dnsObservations by com.wireturn.app.domain.DnsRuntimeTelemetry.observations.collectAsStateWithLifecycle()
    val assetRevision by com.wireturn.app.domain.RoutingAssetStore.revision.collectAsStateWithLifecycle()
    val assetsChanged = remember(session, assetRevision) {
        val path = session?.assetsDirectory
        val source = session?.settings?.happRoutingSource.orEmpty()
        path != null && path != (if (source.isBlank()) com.wireturn.app.domain.GeoAssetsManager.assetsDir(context)
            else com.wireturn.app.domain.SubscriptionRoutingAssets.directory(context, source)).absolutePath
    }
    val running = state == com.wireturn.app.viewmodel.XrayState.Running || state == com.wireturn.app.viewmodel.XrayState.DirectRoute
    val applied = expected != null && session != null && running &&
        com.wireturn.app.data.SharedNetworkSettings.fromLegacy(expected!!) == com.wireturn.app.data.SharedNetworkSettings.fromLegacy(session!!.settings)
    Text(when {
        session?.xray?.routingEnabled == false && running -> "Маршрутизация текущего соединения отключена"
        applied && assetsChanged -> "Базы обновлены. Переподключитесь для применения; сейчас действует предыдущий комплект"
        applied -> "Сохранённые настройки применены к текущему соединению"
        running -> "Соединение ещё использует предыдущие настройки"
        else -> "Применится при подключении"
    }, color = MaterialTheme.colorScheme.primary)
    val actual = session?.settings?.takeIf { running } ?: expected
    actual?.takeUnless { privacyMode }?.let { settings ->
        val display = com.wireturn.app.domain.EffectiveDnsDisplay.resolve(settings,
            rawXray = (coreSession?.clientConfig?.kernelConfig as? com.wireturn.app.data.KernelConfig.Vpn)?.config?.isRawXray == true)
        val notice = display.notice
        if (notice != null) {
            Text(notice, style = MaterialTheme.typography.bodySmall)
            return@let
        }
        val split = display.split
        Text("Bootstrap: ${display.rows.single { it.label == com.wireturn.app.R.string.cd_bootstrap }.value}", style = MaterialTheme.typography.bodySmall)
        if (split == null) Text("DNS маршрутизатора: ${settings.dns}", style = MaterialTheme.typography.bodySmall)
        else {
            AppliedDnsPool("DNS через VPN", split.effectiveRemoteServers, split.remoteSelectionMode,
                dnsObservations["remote"].takeIf { running })
            Text("Аварийный резерв VPN DNS: ${com.wireturn.app.domain.EffectiveDnsDisplay.recovery(split.remoteEmergency)}",
                style = MaterialTheme.typography.bodySmall)
            AppliedDnsPool("Прямой DNS", split.effectiveDomesticServers, split.domesticSelectionMode,
                dnsObservations["domestic"].takeIf { running })
            Text("Аварийный резерв прямого DNS: ${com.wireturn.app.domain.EffectiveDnsDisplay.recovery(split.domesticEmergency)}",
                style = MaterialTheme.typography.bodySmall)
            Text("Bootstrap определяет IP сервера DNS через физическую сеть напрямую (DIRECT), вне VPN. Выбор A/AAAA к bootstrap не относится.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppliedDnsPool(title: String, servers: List<com.wireturn.app.domain.SubscriptionPolicyParser.Resolver>, mode: String,
    observed: com.wireturn.app.domain.DnsRuntimeTelemetry.Observation? = null) {
    val selection = if (mode == "priority") "по порядку списка" else "авто: быстрый и стабильный"
    Text("$title · $selection", style = MaterialTheme.typography.bodySmall)
    Text(com.wireturn.app.domain.EffectiveDnsDisplay.observation(observed, servers.size),
        style = MaterialTheme.typography.bodySmall)
    servers.forEachIndexed { index, server ->
        val bootstrap = server.bootstrapIp?.takeIf { it.isNotBlank() }?.let { " · Bootstrap IP: $it" }.orEmpty()
        Text("${index + 1}. ${server.transport} · ${server.endpoint}$bootstrap", style = MaterialTheme.typography.bodySmall)
    }
}
