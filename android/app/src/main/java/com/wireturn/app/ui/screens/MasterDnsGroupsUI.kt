package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.MasterDnsGroup
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.domain.ProfileRouting
import com.wireturn.app.viewmodel.MainViewModel
import java.util.UUID

private val dnsGroupDraftSaver = Saver<MasterDnsGroup, String>(
    save = { Gson().toJson(it) },
    restore = { runCatching { Gson().fromJson(it, MasterDnsGroup::class.java) }.getOrNull() }
)

private fun dnsRoutingLabel(source: Subscription?): String =
    if (source != null && ProfileRouting.enabled(source)) "Правила подписки" else "Настройки приложения"

/** Kept inside the DNS section header so profile lazy keys and reveal indices remain stable. */
@Composable
internal fun MasterDnsGroupsBlock(vm: MainViewModel, subscriptionsTab: Boolean, onConnected: () -> Unit) {
    val groups by vm.masterDnsGroups.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    val visibleGroups = groups.filter { (it.subscriptionId != null) == subscriptionsTab }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { creating = true }) { Text("＋ Создать группу DNS") }
        if (visibleGroups.isEmpty()) Text("Групп пока нет", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        visibleGroups.forEach { group ->
            key(group.id) { MasterDnsGroupCard(vm, group, profiles, subscriptions, onConnected) }
        }
        Text("Отдельные серверы", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }
    if (creating) {
        val id = rememberSaveable { UUID.randomUUID().toString() }
        val source = if (subscriptionsTab) subscriptions.firstOrNull { sub ->
            profiles.any { it.subscriptionId == sub.id && it.kernelConfig is KernelConfig.MasterDns }
        }?.id else null
        val initial = remember(id) { MasterDnsGroup(id, "", emptyList(), subscriptionId = source) }
        MasterDnsGroupEditor(vm, initial, false, profiles, subscriptions) { creating = false }
    }
}

@Composable
private fun MasterDnsGroupCard(vm: MainViewModel, group: MasterDnsGroup, profiles: List<Profile>,
    subscriptions: List<Subscription>, onConnected: () -> Unit) {
    val activeId by vm.activeMasterDnsGroupId.collectAsStateWithLifecycle()
    val status by CoreServiceState.status.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val selected = activeId == group.id
    val connected = selected && status == CoreStatus.Connected
    val running = selected && status != CoreStatus.Idle && status !is CoreStatus.Error
    val missing = group.memberIds.count { id -> profiles.none { it.id == id && it.kernelConfig is KernelConfig.MasterDns && it.subscriptionId == group.subscriptionId } }
    val connect = rememberVpnConnectAction(vm) {
        connecting = true
        vm.connectMasterDnsGroup(group.id, {
            connecting = false
            onConnected()
        }, { error = it; connecting = false })
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text("${group.memberIds.size} серверов · " +
                if (group.strategy == "round_robin") "По кругу" else "Меньше активных соединений",
                style = MaterialTheme.typography.bodySmall)
            Text(subscriptions.find { it.id == group.subscriptionId }?.name
                ?: if (group.subscriptionId == null) "Мои серверы" else "Подписка недоступна",
                style = MaterialTheme.typography.labelMedium)
            Text(group.memberIds.mapNotNull { id -> profiles.find { it.id == id }?.name }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
            Text("Маршрутизация: ${dnsRoutingLabel(subscriptions.find { it.id == group.subscriptionId })}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (missing > 0) Text("Недоступных участников: $missing. Проверьте состав группы.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(when {
                connected -> "Подключено"
                running -> "Подключение меняется…"
                selected -> "Выбрана"
                else -> "Не подключена"
            }, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editing = true }) { Text("Настроить") }
                Button(enabled = !connecting && (running || missing == 0), onClick = {
                    if (running) vm.stopCore() else connect()
                }) { Text(if (running) "Отключить" else "Подключить") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (editing) MasterDnsGroupEditor(vm, group, true, profiles, subscriptions) { editing = false }
}

@Composable
private fun MasterDnsGroupEditor(vm: MainViewModel, initial: MasterDnsGroup, existing: Boolean,
    profiles: List<Profile>, subscriptions: List<Subscription>, onDismiss: () -> Unit) {
    var draft by rememberSaveable(initial.id, stateSaver = dnsGroupDraftSaver) { mutableStateOf(initial) }
    var sourceMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val candidates = profiles.filter { it.kernelConfig is KernelConfig.MasterDns && it.subscriptionId == draft.subscriptionId }
    val missing = draft.memberIds.filter { id -> candidates.none { it.id == id } }
    val source = subscriptions.find { it.id == draft.subscriptionId }
    val valid = draft.name.isNotBlank() && draft.memberIds.size in 2..8 && missing.isEmpty() &&
        (draft.subscriptionId == null || source != null)
    fun changeSource(id: String?) {
        if (draft.subscriptionId != id) draft = draft.copy(subscriptionId = id, memberIds = emptyList())
        sourceMenu = false
        error = null
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = onDismiss) { Text("Назад") }
                        Text(if (existing) "Настройки группы DNS" else "Новая группа DNS",
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(64)); error = null },
                            enabled = !busy, singleLine = true, label = { Text("Название группы") },
                            modifier = Modifier.fillMaxWidth())
                        Text("Источник серверов", style = MaterialTheme.typography.labelLarge)
                        Box {
                            OutlinedButton(enabled = !busy, onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(source?.name ?: if (draft.subscriptionId == null) "Мои серверы" else "Подписка недоступна")
                            }
                            DropdownMenu(sourceMenu, { sourceMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                                DropdownMenuItem(text = { Text("Мои серверы") }, onClick = { changeSource(null) })
                                subscriptions.forEach { sub ->
                                    DropdownMenuItem(text = { Text(sub.name) }, onClick = { changeSource(sub.id) })
                                }
                            }
                        }
                        Text("Маршрутизация: ${dnsRoutingLabel(source)}", color = MaterialTheme.colorScheme.primary)
                        Text("Правила подписки применяются автоматически, если они заданы; иначе используются настройки приложения.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Участники · ${draft.memberIds.size} из 8", style = MaterialTheme.typography.titleMedium)
                        if (candidates.isEmpty()) Text("В этом источнике нет серверов DNS-туннеля.")
                        candidates.forEach { profile ->
                            val checked = profile.id in draft.memberIds
                            val config = (profile.kernelConfig as KernelConfig.MasterDns).config
                            val enabled = !busy && (checked || draft.memberIds.size < 8)
                            Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox,
                                onValueChange = { selected ->
                                    draft = draft.copy(memberIds = if (selected) draft.memberIds + profile.id else draft.memberIds - profile.id)
                                    error = null
                                }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null, enabled = enabled)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(profile.name)
                                    Text((if (config.useLocalResolvers == true) "Мой набор DNS" else "DNS из профиля") +
                                        " · ${config.resolverList().size} адресов", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        missing.forEach { id ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Сервер недоступен", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                TextButton(enabled = !busy, onClick = { draft = draft.copy(memberIds = draft.memberIds - id) }) { Text("Убрать") }
                            }
                        }
                        if (draft.memberIds.size < 2) Text("Выберите минимум 2 сервера.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Распределение соединений", style = MaterialTheme.typography.titleMedium)
                        listOf("least_connections" to "Меньше активных соединений", "round_robin" to "По кругу").forEach { (value, title) ->
                            Row(Modifier.fillMaxWidth().selectable(draft.strategy == value, enabled = !busy, role = Role.RadioButton,
                                onClick = { draft = draft.copy(strategy = value) }).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draft.strategy == value, onClick = null, enabled = !busy)
                                Text(title, Modifier.padding(start = 12.dp))
                            }
                        }
                        Text("DNS-адреса и режим работы берутся из настроек каждого сервера.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Открытое соединение остаётся на своём сервере. Скорость одного потока не суммируется.",
                            style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = { if (existing) deleting = true else onDismiss() }) {
                            Text(if (existing) "Удалить группу" else "Отмена")
                        }
                        Button(enabled = valid && !busy, onClick = {
                            busy = true
                            vm.saveMasterDnsGroup(draft.copy(name = draft.name.trim()), onDismiss, { error = it; busy = false })
                        }) { Text(if (existing) "Сохранить" else "Создать") }
                    }
                }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Удалить «${draft.name}»?") },
        text = { Text("Серверы сохранятся. Если эта группа подключена, соединение остановится.") },
        confirmButton = { TextButton(onClick = {
            deleting = false
            busy = true
            vm.deleteMasterDnsGroup(initial.id, onDismiss, { error = it; busy = false })
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
}
