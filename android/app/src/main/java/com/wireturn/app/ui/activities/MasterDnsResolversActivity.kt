package com.wireturn.app.ui.activities

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.domain.MasterDnsResolverResult
import com.wireturn.app.ui.activities.kernel.MasterDnsConfigActivity
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MasterDnsResolverViewModel
import java.text.DateFormat
import java.util.Date

class MasterDnsResolversActivity : ComponentActivity() {
    private val model: MasterDnsResolverViewModel by viewModels()

    override fun onStop() {
        super.onStop()
        // A bounded foreground operation. Rotation keeps its ViewModel and work.
        if (!isChangingConfigurations) model.stop()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra("EXTRA_PROFILE_ID") ?: return finish()
        model.load(id)
        setContent {
            // Capture one immutable screen snapshot for this composition. LazyColumn's
            // deferred DSL must not read a newer delegated State while retaining an older
            // nullable config; initial profile loading previously crashed at domains.
            val state = model.state.collectAsStateWithLifecycle().value
            val theme by model.prefs.themeModeFlow.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val dynamic by model.prefs.dynamicThemeFlow.collectAsStateWithLifecycle(false)
            var resultsOpen by rememberSaveable { mutableStateOf(false) }
            var profilesOpen by remember { mutableStateOf(false) }
            var networksOpen by remember { mutableStateOf(false) }
            var sourcesOpen by rememberSaveable { mutableStateOf(false) }
            val config = (state.profile?.kernelConfig as? KernelConfig.MasterDns)?.config
            var localSource by remember(config?.useLocalResolvers, state.profile?.id) { mutableStateOf(config?.useLocalResolvers == true) }
            val editable = !state.busy && !state.applying
            val chooser = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) model.importCatalogue(uri)
            }
            LaunchedEffect(state.profile?.id, state.networkStamp) { resultsOpen = false }
            DisposableEffect(state.busy) {
                if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            fun back() {
                if (resultsOpen) resultsOpen = false
                else { model.stop(); finish() }
            }
            BackHandler { back() }
            WireturnTheme(themeMode = theme, dynamicColor = dynamic) {
                Scaffold(topBar = {
                    AppTopAppBar(if (resultsOpen) "Результаты проверки" else "DNS-резолверы", onBack = { back() })
                }, bottomBar = {
                    if (resultsOpen && state.profile != null && config != null) {
                        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Выбрано: ${state.selected.size}", style = MaterialTheme.typography.titleMedium)
                                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall) }
                                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (state.busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(state.progress, style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = model::stop, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
                                } else {
                                Text(if (state.reconnectsOnApply) "Применение переподключит активный VPN."
                                    else "Выбор меняет только этот профиль. Исходный список подписки сохранится.",
                                    style = MaterialTheme.typography.bodySmall)
                                Button(onClick = model::applySelection,
                                    enabled = editable && (state.activeMeasuredAt != null || state.selected.isEmpty()) && state.selected.size <= 128 &&
                                        (state.selected.isNotEmpty() || config.domains.any { it != state.activeDomain && config.resolverList(it).isNotEmpty() }),
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.selected.isEmpty()) "Отключить этот домен"
                                        else if (state.reconnectsOnApply) "Применить и переподключить (${state.selected.size})"
                                        else "Использовать выбранные (${state.selected.size})")
                                }
                                }
                            }
                        }
                    }
                }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (state.profile == null || config == null) item {
                            Text("Профиль MasterDnsVPN не найден. Вернитесь к списку серверов.")
                        } else {
                            item {
                                Text("Серверы / DNS-туннель", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (resultsOpen) {
                                    Text(state.profile!!.name, style = MaterialTheme.typography.titleLarge)
                                    Text(state.networks.find { it.stamp == state.networkStamp }?.label ?: "Сеть недоступна")
                                } else {
                                    Box {
                                        OutlinedButton(onClick = { profilesOpen = true }, enabled = editable,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                            Text(state.profile!!.name, Modifier.weight(1f)); Text("⌄")
                                        }
                                        DropdownMenu(profilesOpen, { profilesOpen = false }) {
                                            state.profiles.forEach { profile ->
                                                DropdownMenuItem(text = { Text(profile.name) }, onClick = {
                                                    profilesOpen = false; model.selectProfile(profile.id)
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                            if (!resultsOpen) {
                                item { DnsPanel {
                                    Text("Режим: ${config.performanceMode()?.title ?: "Из профиля"}",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("Проверка использует сохранённые параметры профиля и не меняет режим подключения.",
                                        style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(enabled = editable, onClick = {
                                        startActivity(Intent(this@MasterDnsResolversActivity, MasterDnsConfigActivity::class.java)
                                            .putExtra("EXTRA_PROFILE_ID", state.profile!!.id)
                                            .putExtra("EXTRA_EDIT_MODE", true))
                                        finish()
                                    }) { Text("Изменить режим в профиле") }
                                } }
                                item { DnsPanel {
                                    Text("Какие резолверы использовать", style = MaterialTheme.typography.titleMedium)
                                    SourceChoice("Из профиля / подписки", "Адресов: ${config.profileResolverList().size} · исходный список",
                                        !localSource, editable) { localSource = false }
                                    SourceChoice("Мой выбранный набор", "Адресов: ${config.localResolverList().size} · сохраняется при обновлении",
                                        localSource, editable && config.localResolverList().isNotEmpty()) { localSource = true }
                                    if (localSource != (config.useLocalResolvers == true)) {
                                        if (state.reconnectsOnApply) Text("Это активный профиль. Применение переподключит VPN.",
                                            style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = { model.setSource(localSource) }, enabled = editable,
                                            modifier = Modifier.fillMaxWidth()) {
                                            Text(if (state.reconnectsOnApply) "Сохранить и переподключить" else "Применить источник")
                                        }
                                    }
                                } }
                                item { DnsPanel {
                                    Text("База DNS-серверов", style = MaterialTheme.typography.titleMedium)
                                    state.catalogue?.let { catalogue ->
                                        Text("Адресов: ${catalogue.count} · доступна офлайн")
                                        Text("Обновлена: ${date(catalogue.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { sourcesOpen = !sourcesOpen }) {
                                            Text(if (catalogue.partial) "Источники · есть ошибки" else "Источники базы")
                                        }
                                        if (sourcesOpen) catalogue.sources.forEach { source ->
                                            Text(source.name, style = MaterialTheme.typography.labelLarge)
                                            Text(source.error ?: "Адресов: ${source.count} · пропущено строк: ${source.rejected}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (source.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    } ?: Text("Скачайте базу или импортируйте список TXT.", style = MaterialTheme.typography.bodyMedium)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = model::updateCatalogue, enabled = editable, modifier = Modifier.weight(1f)) {
                                            Text(if (state.catalogue == null) "Скачать базу" else "Обновить базу")
                                        }
                                        TextButton(onClick = { chooser.launch("text/*") }, enabled = editable, modifier = Modifier.weight(1f)) {
                                            Text("Импорт TXT")
                                        }
                                    }
                                } }
                                item { DnsPanel {
                                    Text("Найти подходящие резолверы", style = MaterialTheme.typography.titleMedium)
                                    Text("Сеть для проверки", style = MaterialTheme.typography.labelMedium)
                                    Box {
                                        OutlinedButton(onClick = { networksOpen = true }, enabled = editable && state.networks.isNotEmpty(),
                                            modifier = Modifier.fillMaxWidth()) {
                                            Text(state.networks.find { it.stamp == state.networkStamp }?.label ?: "Нет доступной сети",
                                                Modifier.weight(1f)); Text("⌄")
                                        }
                                        DropdownMenu(networksOpen, { networksOpen = false }) {
                                            state.networks.forEach { network ->
                                                DropdownMenuItem(text = { Text(network.label) }, onClick = {
                                                    networksOpen = false; model.selectNetwork(network.stamp)
                                                })
                                            }
                                        }
                                    }
                                    Text("Проверяется туннель до выбранного сервера. Текущее VPN-подключение не переключается.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Мобильная сеть доступна в списке, когда Android предоставляет её приложению.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!state.busy) Button(onClick = { model.scan() },
                                        enabled = editable && state.catalogue != null && state.networkStamp != null,
                                        modifier = Modifier.fillMaxWidth()) { Text("Проверить все домены") }

                                } }
                                items(config.domains, key = { "domain:" + it }) { domain ->
                                    DnsPanel {
                                        Text(domain, style = MaterialTheme.typography.titleMedium)
                                        val effective = config.resolverList(domain)
                                        Text("Используется: ${effective.size} резолверов")
                                        if (effective.isEmpty()) Text("Домен отключён: нет выбранных резолверов",
                                            color = MaterialTheme.colorScheme.error)
                                        val chosen = state.selectedByDomain[domain].orEmpty()
                                        Text("Сохранённый выбор: ${config.localResolverList(domain).size}",
                                            style = MaterialTheme.typography.bodySmall)
                                        if (chosen.isNotEmpty()) Text(chosen.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                        val time = state.domainMeasuredAt[domain]
                                        if (time == null) Text("Нет актуальной проверки для этой сети. Сохранённые адреса не означают успешный тест.",
                                            style = MaterialTheme.typography.bodySmall)
                                        else Text("Проверено: ${date(time)} · прошли ${state.results.count { it.domain == domain && it.valid }}",
                                            style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(onClick = { model.selectDomain(domain); model.scan(domain) },
                                            enabled = editable && state.catalogue != null && state.networkStamp != null,
                                            modifier = Modifier.fillMaxWidth()) { Text("Найти резолверы для домена") }
                                        TextButton(onClick = { model.selectDomain(domain); resultsOpen = true },
                                            enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("Результаты и выбор резолверов") }
                                    }
                                }
                            } else {
                                item {
                                    Text(state.activeDomain.orEmpty(), style = MaterialTheme.typography.titleLarge)
                                    Text("Прошли проверку: ${state.domainResults.count { it.valid }} из ${state.domainResults.size}")
                                    Text("Показаны только проверенные пары домен–резолвер. Адреса вне этого списка не проверены.",
                                        style = MaterialTheme.typography.bodySmall)
                                    state.activeMeasuredAt?.let { Text("Проверено: ${date(it)} · результаты действуют 30 минут",
                                        style = MaterialTheme.typography.bodySmall) }
                                    if (state.activeMeasuredAt == null) Text("Нет актуальной проверки. Сохранённые адреса остаются в профиле; для изменения выполните проверку.",
                                        color = MaterialTheme.colorScheme.error)
                                    else if (!state.scanComplete) Text("Частичная проверка. Доступны только подтверждённые адреса.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Выбираются все адреса, прошедшие туннельную проверку для этого домена. Можно оставить только нужные вручную.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(onClick = model::selectWorking,
                                        enabled = editable && state.activeMeasuredAt != null && state.domainResults.any { it.valid },
                                        modifier = Modifier.fillMaxWidth()) { Text("Выбрать рабочие") }
                                    TextButton(onClick = model::clearSelection, enabled = editable && state.selected.isNotEmpty(),
                                        modifier = Modifier.fillMaxWidth()) { Text("Снять выбор для этого домена") }
                                    if (state.activeMeasuredAt != null && state.domainResults.none { it.valid })
                                        Text("Подходящих резолверов нет. Повторите проверку или используйте другой список.",
                                            style = MaterialTheme.typography.bodySmall)
                                }
                                val sorted = state.domainResults.sortedWith(compareByDescending<MasterDnsResolverResult> { it.valid }
                                    .thenBy { it.resolver })
                                items(sorted, key = { it.resolver + "|" + it.domain }) { result ->
                                    Column {
                                        Row(Modifier.fillMaxWidth().clickable(enabled = editable && result.valid && state.activeMeasuredAt != null) {
                                            model.toggle(result.resolver, result.resolver !in state.selected)
                                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                                            Checkbox(result.resolver in state.selected,
                                                { model.toggle(result.resolver, it) }, enabled = editable && result.valid && state.activeMeasuredAt != null)
                                            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                                Text(result.resolver, style = MaterialTheme.typography.titleMedium)
                                                Text(result.domain, style = MaterialTheme.typography.bodySmall)
                                                if (result.valid) {
                                                    Text("${result.latencyMs} мс · потери ${result.attempts - result.successes} из ${result.attempts}")
                                                    Text("MTU ↑ ${result.upload} · ↓ ${result.download}", style = MaterialTheme.typography.bodySmall)
                                                } else Text("Туннель не прошёл проверку", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                                item {
                                    TextButton(onClick = { resultsOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Вернуться") }
                                }
                            }
                            if (state.busy && !resultsOpen) item { DnsPanel {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(state.progress)
                                OutlinedButton(onClick = model::stop, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
                            } }
                        }
                        if (!resultsOpen) {
                            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                            state.message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                        }
                    }
                }
            }
        }
    }

    private fun date(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
}

@Composable
private fun DnsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SourceChoice(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
