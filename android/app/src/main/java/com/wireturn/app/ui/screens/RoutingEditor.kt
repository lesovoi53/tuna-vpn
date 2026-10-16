package com.wireturn.app.ui.screens

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.*
import com.wireturn.app.ui.screens.kernel.VkChoice

@Composable
fun RoutingEditor(policy: RoutingPolicy, categories: List<String>, installedSource: String, enabled: Boolean, onChange: (RoutingPolicy) -> Unit) {
    var edit by remember { mutableStateOf<Int?>(null) }
    var value by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RouteAction.VPN) }
    var preset by remember { mutableStateOf<RoutingPreset?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Правила сайтов", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Общие правила для всех профилей и групп. После сохранения маршрутизатор применит изменения.", style = MaterialTheme.typography.bodySmall)
        VkChoice("Режим", policy.mode.name, RoutingMode.entries.map { it.name to it.title }, enabled = enabled) { onChange(policy.copy(mode = RoutingMode.valueOf(it))) }
        Text("Без совпадения с правилами: ${policy.fallback}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("DNS: через VPN. Исключённые в настройках VPN приложения обходят эти правила. Собственный DoH приложений обрабатывается как обычное HTTPS-соединение.")
        if (policy.mode == RoutingMode.ALL_VPN) Text("В этом режиме применяются только правила блокировки. Другие правила сохранены, но временно не действуют.")
        OutlinedButton(onClick = { showPresets = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Встроенные профили") }
        if (policy.geoSource.isNotBlank()) Text("База категорий: ${policy.geoSource}. Установлена: ${installedSource.ifBlank { "нет" }}")
        Text("Правила сверху вниз: первое совпадение определяет маршрут.")
        policy.rules.forEachIndexed { i, rule ->
            Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rule.enabled, onCheckedChange = { checked -> onChange(policy.copy(rules = policy.rules.mapIndexed { index, r -> if (index == i) r.copy(enabled = checked) else r })) }, enabled = enabled)
                        Column(Modifier.weight(1f).clickable(enabled = enabled) { edit = i; value = rule.match; action = rule.action; error = "" }) {
                            Text("${i + 1}. ${rule.match}")
                            Text(rule.action.title, color = MaterialTheme.colorScheme.primary)
                            if (rule.match.startsWith("geo")) Text(RoutingPresets.description(rule.match), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row {
                        TextButton(enabled = enabled && policy.rules.size < 500, onClick = { onChange(policy.copy(rules = policy.rules.toMutableList().apply { add(i + 1, rule.copy()) })) }) { Text("Копия") }
                        TextButton(enabled = enabled, onClick = { onChange(policy.copy(rules = policy.rules.filterIndexed { index, _ -> index != i })) }) { Text("Удалить") }
                    }
                    Row {
                        TextButton(enabled = enabled && i > 0, onClick = { val list = policy.rules.toMutableList(); java.util.Collections.swap(list, i, i - 1); onChange(policy.copy(rules = list)) }) { Text("Выше") }
                        TextButton(enabled = enabled && i < policy.rules.lastIndex, onClick = { val list = policy.rules.toMutableList(); java.util.Collections.swap(list, i, i + 1); onChange(policy.copy(rules = list)) }) { Text("Ниже") }
                    }
                }
            }
        }
        policy.warnings().forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Проверяются точные дубли и вложенные домены. Пересечения внутри категорий автоматически не определяются.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { edit = -1; value = ""; action = if (policy.mode == RoutingMode.SELECTED_VPN) RouteAction.VPN else RouteAction.DIRECT; error = "" }, enabled = enabled && policy.rules.size < 500, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Добавить правило / категорию") }
    }
    if (showPresets) AlertDialog(onDismissRequest = { showPresets = false }, title = { Text("Встроенные профили") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoutingPresets.all.forEach { p ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { preset = p; showPresets = false }) {
                    Column(Modifier.padding(16.dp)) { Text(p.title, style = MaterialTheme.typography.titleMedium); Text(p.description) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showPresets = false }) { Text("Закрыть") } })
    preset?.let { p -> AlertDialog(onDismissRequest = { preset = null }, title = { Text(p.title) }, text = { Text(p.description + "\n\nЗаменить текущие правила? После применения можно добавлять категории и менять действия.") }, confirmButton = { TextButton(onClick = { onChange(p.policy); preset = null }) { Text("Применить") } }, dismissButton = { TextButton(onClick = { preset = null }) { Text("Отмена") } }) }
    edit?.let { index -> AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (index < 0) "Новое правило" else "Редактировать правило") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VkChoice("Действие", action.name, RouteAction.entries.map { it.name to it.title }) { action = RouteAction.valueOf(it) }
            OutlinedTextField(value, { value = it; error = "" }, label = { Text("Домен, IP / подсеть или категория") }, supportingText = { Text("example.com, full:host.example.com, 192.0.2.0/24, geosite:youtube. Одно условие на правило.") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { search = ""; showCategories = true }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать из установленной базы") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        var normalized = value.trim()
        if (!normalized.contains(':') && !normalized.contains('/') && normalized.any { it.isLetter() }) normalized = "domain:$normalized"
        if (!RoutingPolicy.validMatch(normalized)) { error = "Проверьте формат условия"; return@TextButton }
        val r = RoutingRule(normalized, action, if (index >= 0) policy.rules[index].enabled else true)
        val updated = if (index < 0) policy.rules + r else policy.rules.mapIndexed { i, old -> if (i == index) r else old }
        onChange(policy.copy(rules = updated, geoSource = if (policy.geoSource.isBlank() && normalized.startsWith("geo") && installedSource.isNotBlank()) installedSource else policy.geoSource)); edit = null
    }) { Text("Готово") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Отмена") } }) }
    if (showCategories) AlertDialog(onDismissRequest = { showCategories = false }, title = { Text("Категории установленной базы") }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text("Поиск категории") })
            if (categories.isEmpty()) Text("Сначала скачайте гео-ресурсы на основном экране настроек.")
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(categories.filter { it.contains(search, true) }) { category ->
                    Column(Modifier.fillMaxWidth().clickable { value = category; showCategories = false }.padding(vertical = 14.dp)) { Text(category); Text(RoutingPresets.description(category), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showCategories = false }) { Text("Закрыть") } })
}
