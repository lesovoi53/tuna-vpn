package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.*
import com.wireturn.app.domain.SubscriptionPolicyParser
import com.wireturn.app.domain.SubscriptionRuleOverrides
import kotlinx.coroutines.launch

@Composable
fun SubscriptionRulesEditor(subscription: Subscription, source: SubscriptionPolicyParser.HappPolicy, editable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(subscription.id) { mutableStateOf(false) }
    var error by remember(subscription.id) { mutableStateOf("") }
    var adding by remember(subscription.id) { mutableStateOf(false) }
    var editingIndex by remember(subscription.id) { mutableStateOf<Int?>(null) }
    var editingRule by remember(subscription.id) { mutableStateOf<RoutingRule?>(null) }
    var resetting by remember(subscription.id) { mutableStateOf(false) }
    var match by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RouteAction.VPN) }
    val loaded = remember(subscription.routingOverridesJson) { runCatching { SubscriptionRuleOverrides.load(subscription.routingOverridesJson) } }
    val overrides = loaded.getOrNull()
    fun save(next: SubscriptionRuleOverrides) {
        busy = true
        error = ""
        scope.launch {
            try {
                AppPreferences(context).setSubscriptionRuleOverrides(subscription.id, subscription.routingSource.orEmpty(), subscription.routingOverridesJson, next)
                adding = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Не удалось сохранить правила"
            } finally { busy = false }
        }
    }
    if (overrides == null) {
        Text("Не удалось прочитать сохранённые пользовательские правила", color = MaterialTheme.colorScheme.error)
        return
    }
    Text("Правила выполняются сверху вниз: первое совпадение определяет маршрут. Пользовательские правила идут перед правилами подписки. Изменения сохраняются сразу и сохранятся после обновления подписки. Для применения переподключитесь.", style = MaterialTheme.typography.bodySmall)
    overrides.userRules.forEachIndexed { index, rule ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rule.enabled, enabled = editable && !busy, onCheckedChange = { enabled ->
                        save(overrides.copy(userRules = overrides.userRules.mapIndexed { i, r -> if (i == index) r.copy(enabled = enabled) else r }))
                    })
                    Column(Modifier.weight(1f)) {
                        Text("${index + 1}. Пользовательское правило", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text(rule.match)
                        Text(rule.action.title)
                    }
                }
                Row {
                    TextButton(enabled = editable && !busy, onClick = { editingIndex = index; editingRule = rule; match = rule.match; action = rule.action; error = ""; adding = true }) { Text("Изменить") }
                    TextButton(enabled = editable && !busy, onClick = { save(overrides.copy(userRules = overrides.userRules.filterIndexed { i, _ -> i != index })) }) { Text("Удалить") }
                }
                if (overrides.userRules.size > 1) Row {
                    TextButton(enabled = editable && !busy && index > 0, onClick = { save(overrides.moveUserRule(index, index - 1)) }) { Text("Выше") }
                    TextButton(enabled = editable && !busy && index < overrides.userRules.lastIndex, onClick = { save(overrides.moveUserRule(index, index + 1)) }) { Text("Ниже") }
                }
            }
        }
    }
    OutlinedButton(enabled = editable && !busy && overrides.userRules.size + source.routing.rules.size < 500, onClick = { editingIndex = null; editingRule = null; match = ""; action = RouteAction.VPN; error = ""; adding = true }) { Text("Добавить пользовательское правило") }
    TextButton(enabled = editable && !busy && (overrides.disabledRules.isNotEmpty() || overrides.enabledRules.isNotEmpty()), onClick = { resetting = true }) { Text("Вернуть переключатели подписки") }
    source.routing.rules.forEach { rule ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(overrides.isEnabled(rule), enabled = editable && !busy, onCheckedChange = { save(overrides.toggle(rule, it)) })
                Column(Modifier.weight(1f)) {
                    Text("Из подписки", style = MaterialTheme.typography.labelMedium)
                    Text(rule.match)
                    Text(rule.action.title, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (resetting) AlertDialog(onDismissRequest = { resetting = false }, title = { Text("Вернуть настройки подписки?") },
        text = { Text("Переключатели правил вернутся к значениям подписки. Ваши собственные правила и их порядок сохранятся.") },
        confirmButton = { TextButton(onClick = { resetting = false; save(overrides.resetSource()) }) { Text("Вернуть") } },
        dismissButton = { TextButton(onClick = { resetting = false }) { Text("Отмена") } })
    if (adding) AlertDialog(onDismissRequest = { if (!busy) adding = false }, title = { Text("Пользовательское правило") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.wireturn.app.ui.screens.kernel.VkChoice("Действие", action.name, RouteAction.entries.map { it.name to it.title }, enabled = !busy) { action = RouteAction.valueOf(it) }
            OutlinedTextField(match, { match = it; error = "" }, enabled = !busy, label = { Text("Домен, IP / подсеть или категория") }, supportingText = { Text("example.com, 192.0.2.0/24, geosite:youtube") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val value = match.trim().let { if (!it.contains(':') && !it.contains('/') && it.any(Char::isLetter)) "domain:$it" else it }
        if (!RoutingPolicy.validMatch(value)) error = "Проверьте формат условия"
        else {
            val index = editingIndex
            if (index != null && overrides.userRules.getOrNull(index) != editingRule) error = "Правила изменились. Закройте окно и откройте правило заново."
            else save(overrides.copy(userRules = if (index == null) overrides.userRules + RoutingRule(value, action)
                    else overrides.userRules.mapIndexed { i, rule -> if (i == index) rule.copy(match = value, action = action) else rule }))
        }
    }) { Text(if (editingIndex == null) "Добавить" else "Сохранить") } }, dismissButton = { TextButton(enabled = !busy, onClick = { adding = false }) { Text("Отмена") } })
}
