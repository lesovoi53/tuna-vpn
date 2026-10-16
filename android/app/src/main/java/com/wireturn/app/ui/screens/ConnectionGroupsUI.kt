package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.data.*
import com.wireturn.app.domain.ManualGroupPolicy
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.R
import java.util.UUID

private val groupDraftSaver = Saver<ConnectionGroup, String>(
    save = { com.google.gson.Gson().toJson(it) },
    restore = { runCatching { com.google.gson.Gson().fromJson(it, ConnectionGroup::class.java) }.getOrNull() }
)
private val GroupProbeMethod.displayName: String get() = when (this) {
    GroupProbeMethod.GET -> "HTTP GET"
    GroupProbeMethod.HEAD -> "HTTP HEAD"
}

@Composable
fun ConnectionGroupRow(vm: MainViewModel, group: ConnectionGroup, onSelected: () -> Unit) {
    if (group.type != GroupTestType.URL_TEST || !ManualGroupPolicy.allows(group)) return
    val activeId by vm.activeConnectionGroupId.collectAsStateWithLifecycle()
    val state by vm.groupRunState.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable(group.id) { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val connect = rememberVpnConnectAction(vm) { vm.connectGroup(group, onSelected) }
    Card(onClick = { details = true }, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (activeId == group.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text("URL-test · ${pluralStringResource(R.plurals.connection_servers, group.memberIds.size, group.memberIds.size)}", style = MaterialTheme.typography.bodySmall)
                if (group.repairRequired) Text("Проверьте состав и маршрутизацию группы после обновления", color = MaterialTheme.colorScheme.error)
                if (group.memberIds.any { id -> profiles.none { it.id == id } }) Text("Часть участников удалена из подписки — измените состав группы", color = MaterialTheme.colorScheme.error)
                if (state.groupId == group.id && state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = connect) { Icon(painterResource(R.drawable.power_24px), "Подключить группу") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.more_vert_24px), "Меню группы") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Проверить URL-test") }, onClick = { menu = false; testing = true })
                    DropdownMenuItem(text = { Text("Выбрать сервер вручную") }, onClick = { menu = false; manual = true })
                    DropdownMenuItem(text = { Text("Редактировать") }, onClick = { menu = false; editing = true })
                    DropdownMenuItem(text = { Text("Удалить группу", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
                }
            }
        }
    }
    if (details) GroupSavedResultsDialog(group, profiles, vm, { details = false }, {
        vm.connectGroup(group) { details = false; onSelected() }
    })
    if (manual) AlertDialog(onDismissRequest = { manual = false }, title = { Text("Ручной выбор — автовыбор остановится") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { group.memberIds.forEach { id ->
            TextButton(onClick = { manual = false; vm.selectGroupMember(group, id) }) { Text(profiles.find { it.id == id }?.name ?: id) }
        } } }, confirmButton = { TextButton(onClick = { manual = false }) { Text("Отмена") } })
    if (testing) GroupTestDialog(vm, group, profiles) { testing = false }
    if (editing) GroupEditor(group, profiles, { editing = false }, { vm.saveConnectionGroup(it); editing = false }, { vm.deleteConnectionGroup(group.id); editing = false })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Удалить «${group.name}»?") },
        text = { Text("Серверы останутся в списке подключений. Удалится только группа.") },
        confirmButton = { TextButton(onClick = { deleting = false; vm.deleteConnectionGroup(group.id) }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
}

@Composable
internal fun GroupTestDialog(vm: MainViewModel, group: ConnectionGroup, profiles: List<Profile>, type: GroupTestType = group.type, close: () -> Unit) {
    if (group.type != GroupTestType.URL_TEST || type != GroupTestType.URL_TEST || !ManualGroupPolicy.allows(group)) return
    val run by vm.groupRunState.collectAsStateWithLifecycle()
    val urls by vm.profileUrlTestState.collectAsStateWithLifecycle()
    val state = run.takeIf { it.groupId == group.id && (it.testType == null || it.testType == type) }
    var sorted by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${group.name} · URL-test", style = MaterialTheme.typography.titleLarge)
                Text("Проверка идёт через собственный туннель профиля без цепочки и общих правил; текущее подключение не меняется.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${group.effectiveProbeMethod.displayName} по HTTPS: SOCKS, соединение с сайтом, TLS и HTTP-заголовки. Не ICMP-пинг. Медиана ${group.samples} запросов.",
                    style = MaterialTheme.typography.bodySmall)
                if (state != null) {
                    Text(state.message)
                    if (state.busy) LinearProgressIndicator(progress = { state.results.size.toFloat() / group.memberIds.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("${state.results.size} / ${group.memberIds.size}", style = MaterialTheme.typography.bodySmall)
                }
                val results = state?.results.orEmpty().associateBy { it.profileId }
                val ids = if (!sorted) group.memberIds else group.memberIds.sortedWith(compareBy<String> { results[it]?.error != null || results[it] == null }
                    .thenBy { results[it]?.latencyMs ?: Long.MAX_VALUE })
                ids.forEach { id ->
                    val result = results[id]
                    val testing = state?.busy == true && state.testingId == id
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(profiles.find { it.id == id }?.name ?: "Удалён", Modifier.weight(1f))
                        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(when {
                            testing -> "Проверка…"
                            result?.error != null -> "Ошибка"
                            result != null -> "${result.latencyMs} мс"
                            state?.busy == true -> "Ожидание"
                            else -> "—"
                        }, color = if (result?.error != null) MaterialTheme.colorScheme.error else if (result != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (result?.error != null) Text(result.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    HorizontalDivider()
                }
                TextButton(enabled = state?.results?.isNotEmpty() == true, onClick = { sorted = !sorted }) { Text(if (sorted) "Исходный порядок" else "Сортировать по результату") }
                AutomaticProbeSelectionNotice(run.automatic) { vm.cancelGroupTest() }
                Row {
                    if (state?.busy == true) TextButton(onClick = { vm.cancelGroupTest() }) { Text("Остановить") }
                    else TextButton(enabled = !run.busy && !run.automatic && !urls.busy, onClick = {
                        vm.testConnectionGroup(group, GroupTestType.URL_TEST)
                    }) { Text("Проверить") }
                    TextButton(onClick = close) { Text(if (state?.busy == true) "Свернуть" else "Закрыть") }
                }
            }
        }
    }
}

@Composable
fun ConnectionGroupsButton(vm: MainViewModel, category: ConnectionCategory) {
    if (category != ConnectionCategory.VPN) return
    var creating by rememberSaveable { mutableStateOf(false) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    TextButton(onClick = { creating = true }) { Text(stringResource(R.string.profiles_create_group)) }
    if (creating) {
        val draftId = rememberSaveable { UUID.randomUUID().toString() }
        val initial = remember(draftId) { ConnectionGroup(draftId, "", category, GroupTestType.URL_TEST, emptyList(),
            family = GroupFamily.VPN) }
        GroupEditor(initial, profiles, { creating = false }, { vm.saveConnectionGroup(it); creating = false })
    }
}

@Composable
private fun GroupEditor(initial: ConnectionGroup, profiles: List<Profile>, onDismiss: () -> Unit,
                        onSave: (ConnectionGroup) -> Unit, onDelete: (() -> Unit)? = null) {
    if (initial.type != GroupTestType.URL_TEST || !ManualGroupPolicy.allows(initial)) return
    var group by rememberSaveable(initial.id, stateSaver = groupDraftSaver) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var editKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editTitle by rememberSaveable { mutableStateOf("") }
    var editValue by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf(false) }
    var members by rememberSaveable { mutableStateOf(false) }
    var selectedMembers by rememberSaveable(stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })) { mutableStateOf(group.memberIds) }
    var routing by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun edit(key: String, title: String, value: String) { editKey = key; editTitle = title; editValue = value }
    fun duration(value: Int) = if (value > 0 && value % 60 == 0) "${value / 60}m" else "${value}s"
    fun save() {
        try {
            val checked = group.copy(repairRequired = false)
            require(checked.type == GroupTestType.URL_TEST && ManualGroupPolicy.allows(checked)) { "Группы URL-test доступны только в разделе VPN" }
            checked.validateForTesting(profiles)
            onSave(checked)
        }
        catch (e: Exception) { error = e.message ?: "Проверьте настройки" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.close_24px), "Закрыть") }
                    Text("Конфигурация\nпрофиля", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(enabled = onDelete != null, onClick = { deleting = true }) { Icon(painterResource(R.drawable.delete_24px), "Удалить группу") }
                    IconButton(onClick = { save() }) { Icon(painterResource(R.drawable.check_24px), "Сохранить") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    GroupSettingRow("Имя профиля", group.name.ifBlank { "Не задано" }, R.drawable.edit_24px) { edit("name", "Имя профиля", group.name) }
                    Text("Режим: URL-test", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    GroupSettingRow("URL проверки подключения", group.testUrl, R.drawable.vpn_key_24px) { edit("url", "URL проверки подключения", group.testUrl) }
                    GroupSettingRow("Метод проверки", group.effectiveProbeMethod.displayName, R.drawable.settings_24px) { method = true }
                    GroupSettingRow("Допуск URLTest", "${group.improvementMs} мс", R.drawable.settings_24px) {
                        edit("tolerance", "Минимальное улучшение", group.improvementMs.toString())
                    }
                    GroupSettingRow("Тип", "Список · ${pluralStringResource(R.plurals.deep_link_import_profiles_count, group.memberIds.size, group.memberIds.size)}", R.drawable.check_box_24px) { selectedMembers = group.memberIds; members = true }
                    GroupSettingRow("Порядок выбора", if (group.effectiveSelectionMode == GroupSelectionMode.PRIORITY) "Приоритетный резерв: сверху вниз" else "Лучший результат теста", R.drawable.settings_24px) {
                        group = group.copy(selectionMode = if (group.effectiveSelectionMode == GroupSelectionMode.PRIORITY) GroupSelectionMode.BEST else GroupSelectionMode.PRIORITY)
                    }
                    GroupSettingSwitch("Повторять тест и выбирать сервер", group.automatic) { group = group.copy(automatic = it) }
                    if (group.automatic) {
                        GroupSettingRow("Интервал повторной проверки", duration(group.intervalSeconds), R.drawable.refresh_24px) { edit("interval", "Интервал: s — секунды, m — минуты", duration(group.intervalSeconds)) }
                        Text("После подключения группы: пауза между тестами ${duration(group.intervalSeconds)}. Смена сервера учитывает допуск, удержание и подтверждения. Может прервать соединения. Работает, пока жив процесс приложения.", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    GroupSettingSwitch("Тест при подключении", group.testOnConnect) { group = group.copy(testOnConnect = it) }
                    GroupSettingRow("Дополнительные настройки", if (advanced) "Скрыть" else "Таймаут, маршрутизация, мобильная сеть", R.drawable.settings_24px) { advanced = !advanced }
                    if (advanced) {
                        GroupSettingRow("Таймаут запроса", duration(group.timeoutSeconds), R.drawable.refresh_24px) { edit("timeout", "Таймаут запроса", duration(group.timeoutSeconds)) }
                        GroupSettingRow("Ожидаемый HTTP статус", group.expectedStatus.toString(), R.drawable.check_24px) { edit("status", "HTTP статус", group.expectedStatus.toString()) }
                        GroupSettingRow("Удержание сервера", duration(group.holdSeconds), R.drawable.refresh_24px) { edit("hold", "Минимум между переключениями", duration(group.holdSeconds)) }
                        GroupSettingRow("Подтверждений улучшения / отказа", group.confirmations.toString(), R.drawable.check_24px) { edit("confirmations", "Подтверждений: 1–10", group.confirmations.toString()) }
                        GroupSettingRow("Срок годности результата", duration(group.freshnessSeconds), R.drawable.refresh_24px) { edit("freshness", "Срок годности", duration(group.freshnessSeconds)) }
                        GroupSettingRow("Запросов на сервер", group.samples.toString(), R.drawable.refresh_24px) { edit("samples", "Запросов: 1–5", group.samples.toString()) }
                        GroupSettingRow("Цепочка группы (профиль-источник)", profiles.find { it.id == group.routingProfileId }?.name ?: "Не задана", R.drawable.settings_24px) { routing = true }
                        GroupSettingSwitch("Тестировать по мобильной сети", group.allowMobile) { group = group.copy(allowMobile = it) }
                        Text("DNS и правила общие для приложения. Здесь выбирается только цепочка. Проверка не переключает VPN; автовыбор участников выполняет TUNA.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { selectedMembers = group.memberIds; members = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), shape = MaterialTheme.shapes.large) { Text("Добавить профиль", Modifier.padding(12.dp)) }
                    group.memberIds.forEachIndexed { index, id ->
                        if (group.effectiveSelectionMode == GroupSelectionMode.PRIORITY && index > 0) TextButton(onClick = {
                            val order = group.memberIds.toMutableList(); java.util.Collections.swap(order, index, index - 1); group = group.copy(memberIds = order)
                        }) { Text("Поднять приоритет: ${profiles.find { it.id == id }?.name.orEmpty()}") }
                        GroupSettingRow(profiles.find { it.id == id }?.name ?: "Удалённый профиль", "Участник группы", R.drawable.vpn_key_24px) { selectedMembers = group.memberIds; members = true }
                    }
                }
            }
        }
    }
    if (editKey != null) AlertDialog(onDismissRequest = { editKey = null }, title = { Text(editTitle) },
        text = { OutlinedTextField(editValue, { editValue = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            try {
                val value = editValue.trim()
                fun number(): Int = when {
                    value.endsWith("m") -> Math.multiplyExact(value.dropLast(1).toInt(), 60)
                    value.endsWith("s") -> value.dropLast(1).toInt()
                    else -> value.toInt()
                }
                group = when (editKey) {
                    "name" -> group.copy(name = value)
                    "url" -> group.copy(testUrl = value)
                    "interval" -> group.copy(intervalSeconds = number())
                    "tolerance" -> group.copy(improvementMs = number())
                    "timeout" -> group.copy(timeoutSeconds = number())
                    "status" -> group.copy(expectedStatus = number())
                    "hold" -> group.copy(holdSeconds = number())
                    "freshness" -> group.copy(freshnessSeconds = number())
                    "confirmations" -> group.copy(confirmations = number())
                    "samples" -> group.copy(samples = number())
                    else -> group
                }
                editKey = null
            } catch (_: Exception) { error = "Некорректное значение" }
        }) { Text("ОК") } }, dismissButton = { TextButton(onClick = { editKey = null }) { Text("Отмена") } })
    if (method) AlertDialog(onDismissRequest = { method = false }, title = { Text("Метод проверки") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GroupProbeMethod.entries.forEach { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = group.effectiveProbeMethod == value, role = Role.RadioButton,
                        onClick = { group = group.copy(probeMethod = value); method = false })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(group.effectiveProbeMethod == value, null); Text(value.displayName)
                }
            }
            Text("Оба метода включают подключение, TLS и получение HTTP-заголовков. HEAD запрашивает ответ без тела; сайт должен поддерживать этот метод.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { method = false }) { Text("Закрыть") } })
    if (members) AlertDialog(onDismissRequest = { members = false }, title = { Text("Добавить профили") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val selectedFamily = group.family ?: profiles.firstOrNull { it.id in selectedMembers }?.groupFamily
            fun eligible(profile: Profile) = ManualGroupPolicy.canSelectMember(group, profile) &&
                (selectedFamily == null || profile.groupFamily == selectedFamily)
            // Previously selected, now ineligible members stay removable while repairing a group.
            profiles.filter { eligible(it) || it.id in selectedMembers }.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(profile.id in selectedMembers, { enabled -> selectedMembers = if (enabled && eligible(profile)) selectedMembers + profile.id else selectedMembers - profile.id },
                        enabled = eligible(profile) || profile.id in selectedMembers)
                    Column(Modifier.weight(1f)) {
                        Text(profile.name)
                        if (!eligible(profile)) Text(stringResource(R.string.group_member_unavailable), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        group = group.copy(memberIds = selectedMembers, routingProfileId = group.routingProfileId.takeIf { it in selectedMembers } ?: selectedMembers.firstOrNull().orEmpty())
        members = false
    }) { Text("Добавить") } }, dismissButton = { TextButton(onClick = { members = false }) { Text("Отмена") } })
    if (routing) AlertDialog(onDismissRequest = { routing = false }, title = { Text("Маршрутизация из профиля") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { profiles.filter { it.id in group.memberIds }.forEach { profile ->
            Row(Modifier.clickable { group = group.copy(routingProfileId = profile.id); routing = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(group.routingProfileId == profile.id, null); Text(profile.name) }
        } }
    }, confirmButton = { TextButton(onClick = { routing = false }) { Text("Закрыть") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Удалить группу?") }, text = { Text("Серверы останутся в списке подключений.") },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete?.invoke() }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Проверьте настройки") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("ОК") } }) }
}

@Composable
private fun GroupSettingRow(title: String, value: String, icon: Int, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupSettingSwitch(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    Card(onClick = { change(!checked) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.settings_24px), null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(24.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked, change)
        }
    }
}
