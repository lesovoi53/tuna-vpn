package com.wireturn.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.wireturn.app.data.*
import com.wireturn.app.domain.ProbeKind
import com.wireturn.app.domain.SavedProbeResult
import com.wireturn.app.domain.latestProbe
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.kernel.KernelRegistry
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** Presentation-only filtering: never rewrites subscription order or uses stale successes. */
internal fun filterAndSortProbedProfiles(
    profiles: List<Profile>, history: List<SavedProbeResult>, successfulOnly: Boolean, latencyOrder: Boolean
): List<Profile> {
    if (!successfulOnly && !latencyOrder) return profiles
    val latest = history.asSequence().filter { it.kind == ProbeKind.URL }.groupBy { it.profileId }
        .mapValues { (_, results) -> results.maxBy { it.measuredAt } }
    // Hash once per profile, not in the sorting comparator (large subscriptions can have 1000+ rows).
    val latency = profiles.associate { profile -> profile.id to latest[profile.id]
        ?.takeIf { it.error == null && !it.isStale(profile) }?.latencyMs?.takeIf { it >= 0 } }
    val filtered = if (successfulOnly) profiles.filter { latency[it.id] != null } else profiles
    return if (latencyOrder) filtered.sortedBy { latency[it.id] ?: Long.MAX_VALUE } else filtered
}

@Composable
internal fun rememberVisibleProbeClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); delay(60_000) }
        }
    }
    return now
}

internal fun probeRelativeAge(measuredAt: Long, now: Long): String {
    if (measuredAt > now + 60_000) return "проверьте время устройства"
    val minutes = ((now - measuredAt).coerceAtLeast(0) / 60_000)
    return when {
        minutes == 0L -> "только что"
        minutes < 60 -> "$minutes мин. назад"
        minutes < 1440 -> "${minutes / 60} ч. назад"
        else -> "${minutes / 1440} дн. назад"
    }
}

@Composable
internal fun AutomaticProbeSelectionNotice(automatic: Boolean, onStop: () -> Unit) {
    if (automatic) Column {
        Text("Сначала остановите автовыбор для ручной проверки. Текущий туннель останется подключённым.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onStop) { Text("Остановить автовыбор") }
    }
}

@Composable
fun SavedProbeResultsContent(profile: Profile, history: List<SavedProbeResult>, groupId: String? = null,
    now: Long = rememberVisibleProbeClock()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        run {
            val result = history.latestProbe(profile, ProbeKind.URL, groupId)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("URL-test", style = MaterialTheme.typography.titleSmall)
                    Text(when {
                        result == null -> "Ещё не проверен"
                        result.error != null -> result.error
                        else -> result.latencyMs?.let { "$it мс" } ?: "Нет результата"
                    }, color = if (result?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (result != null) {
                        Text("${probeRelativeAge(result.measuredAt, now)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.measuredAt))}",
                            style = MaterialTheme.typography.bodySmall)
                        if (result.isStale(profile)) Text("Устарел: параметры подключения изменились", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileProbeDetailsDialog(profile: Profile, viewModel: MainViewModel, onDismiss: () -> Unit,
    onConnect: () -> Unit = { viewModel.connectProfile(profile.id, onDismiss) }) {
    val connect = rememberVpnConnectAction(viewModel, onConnect)
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val currentProfile = profiles.firstOrNull { it.id == profile.id } ?: profile
    val history by viewModel.probeHistory.collectAsStateWithLifecycle()
    val url by viewModel.profileUrlTestState.collectAsStateWithLifecycle()
    val group by viewModel.groupRunState.collectAsStateWithLifecycle()
    var chooseServer by remember { mutableStateOf(false) }
    val idle = !url.busy && !group.busy && !group.automatic
    val activeSession by com.wireturn.app.CoreServiceState.session.collectAsStateWithLifecycle()
    val coreStatus by com.wireturn.app.CoreServiceState.status.collectAsStateWithLifecycle()
    val requiresActive = com.wireturn.app.domain.ProbeEligibility.requiresActive(currentProfile)
    val canProbe = !requiresActive || (activeSession?.profileId == currentProfile.id && coreStatus == com.wireturn.app.CoreStatus.Connected)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(currentProfile.name, style = MaterialTheme.typography.titleLarge)
                Text(profileSummaryParts(currentProfile).primary, style = MaterialTheme.typography.bodyMedium)
                if ((currentProfile.kernelConfig as? KernelConfig.Vpn)?.config?.isRawXray == true) {
                    Text("Полный JSON Xray: все outbounds и правила сохраняются в одном профиле. Редактор открывает исходную конфигурацию целиком.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (currentProfile.retainedFromSubscription != null) Text("Сохранён локально · удалён из подписки", color = MaterialTheme.colorScheme.primary)
                Text("Последние результаты сохраняются. Открытие карточки не запускает тест и не меняет подключение.", style = MaterialTheme.typography.bodySmall)
                SavedProbeResultsContent(currentProfile, history)
                run {
                    if (requiresActive) Text(if (canProbe) "Тест через активное подключение этого сервера."
                        else "Сначала подключите этот сервер, затем запустите URL-test.", style = MaterialTheme.typography.bodySmall)
                    AutomaticProbeSelectionNotice(group.automatic) { viewModel.cancelGroupTest() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = idle && canProbe, onClick = { viewModel.testProfileUrls("profile:${profile.id}", listOf(currentProfile)) }) { Text("URL-test") }
                    }
                    if (url.busy && url.owner == "profile:${profile.id}") {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(url.labelFor(currentProfile) ?: "Проверка…")
                        TextButton(onClick = { viewModel.cancelProfileUrlTests() }) { Text("Остановить URL-test") }
                    }
                }
                Button(onClick = connect, modifier = Modifier.fillMaxWidth()) { Text("Подключить") }
                TextButton(onClick = { chooseServer = true }) { Text("Выбрать другой сервер") }
                TextButton(onClick = {
                    context.startActivity(Intent(context, KernelRegistry.get(currentProfile.kernelVariant).configActivityClass).apply {
                        putExtra("EXTRA_EDIT_MODE", true)
                        putExtra("EXTRA_PROFILE_NAME", currentProfile.name)
                        putExtra("EXTRA_PROFILE_ID", currentProfile.id)
                    })
                }) { Text("Редактировать подключение") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
    if (chooseServer) ConnectionSwitchDialog(viewModel, source = currentProfile) { chooseServer = false; onDismiss() }
}

@Composable
fun GroupSavedResultsDialog(group: ConnectionGroup, profiles: List<Profile>, viewModel: MainViewModel,
    onDismiss: () -> Unit, onConnect: () -> Unit = { viewModel.connectGroup(group, onDismiss) }) {
    if (group.type != GroupTestType.URL_TEST || !com.wireturn.app.domain.ManualGroupPolicy.allows(group)) return
    val connect = rememberVpnConnectAction(viewModel, onConnect)
    val history by viewModel.probeHistory.collectAsStateWithLifecycle()
    val run by viewModel.groupRunState.collectAsStateWithLifecycle()
    val urls by viewModel.profileUrlTestState.collectAsStateWithLifecycle()
    var testType by remember(group.id) { mutableStateOf<GroupTestType?>(null) }
    var chooseServer by remember { mutableStateOf(false) }
    val idle = !group.repairRequired && !run.busy && !run.automatic && !urls.busy
    val now = rememberVisibleProbeClock()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge)
                if (group.repairRequired) Text("Состав группы изменился после обновления. Проверьте участников и маршрутизацию в редакторе, затем сохраните группу.", color = MaterialTheme.colorScheme.error)
                Text("Сохранённые результаты URL-test. Просмотр не запускает проверку.", style = MaterialTheme.typography.bodySmall)
                group.memberIds.forEach { id ->
                    val profile = profiles.find { it.id == id }
                    Text(profile?.name ?: "Профиль удалён", style = MaterialTheme.typography.titleMedium)
                    if (profile != null) SavedProbeResultsContent(profile, history, group.id, now)
                }
                if (run.groupId == group.id && run.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(run.message)
                    TextButton(onClick = { testType = run.testType ?: group.type }) { Text("Ход проверки") }
                    TextButton(onClick = { viewModel.cancelGroupTest() }) { Text("Остановить") }
                }
                AutomaticProbeSelectionNotice(run.automatic) { viewModel.cancelGroupTest() }
                Row {
                    TextButton(enabled = idle, onClick = { viewModel.testConnectionGroup(group, GroupTestType.URL_TEST); testType = GroupTestType.URL_TEST }) { Text("URL-test") }
                }
                Button(onClick = connect, enabled = !group.repairRequired, modifier = Modifier.fillMaxWidth()) { Text("Подключить группу") }
                TextButton(onClick = { chooseServer = true }) { Text("Выбрать другой сервер") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
    testType?.let { type -> GroupTestDialog(viewModel, group, profiles, type) { testType = null } }
    if (chooseServer) ConnectionSwitchDialog(viewModel) { chooseServer = false; onDismiss() }
}
