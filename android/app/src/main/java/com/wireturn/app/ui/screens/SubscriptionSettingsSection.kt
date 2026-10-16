package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Subscription
import com.wireturn.app.domain.SubscriptionSettingsRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
fun SubscriptionSettingsSection(subscriptions: List<Subscription>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context.applicationContext) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Дополнительные настройки из подписки", style = MaterialTheme.typography.titleMedium)
        Text("Применяется только выбранная копия настроек. Автоматическая замена при обновлении выключена. Для сетевых изменений переподключите VPN.", style = MaterialTheme.typography.bodySmall)
        subscriptions.filter { !it.tunaDocument.isNullOrBlank() }.forEach { sub ->
            val document = remember(sub.tunaDocument) { JsonParser.parseString(sub.tunaDocument).asJsonObject }
            val values = remember(sub.tunaDocument) { SubscriptionSettingsRegistry.values(document) }
            var selected by remember(sub.tunaDocument) { mutableStateOf(emptySet<String>()) }
            Text(sub.name, style = MaterialTheme.typography.titleSmall)
            values.filterKeys { it !in SubscriptionSettingsRegistry.hidden }.forEach { (path, value) ->
                val field = SubscriptionSettingsRegistry.fields[path]
                if (path in SubscriptionSettingsRegistry.localOnly) Text("${field?.label}: общая настройка приложения, подпиской не изменяется", style = MaterialTheme.typography.bodySmall)
                else if (field == null) Text("Пока не поддерживается: $path", style = MaterialTheme.typography.bodySmall)
                else Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = path in selected, enabled = !busy, onCheckedChange = { checked -> selected = if (checked) selected + path else selected - path })
                    Text("${field.label}: $value", modifier = Modifier.padding(top = 12.dp).weight(1f))
                }
            }
            val resetCount = document.getAsJsonObject("settings")?.getAsJsonArray("resetPaths")?.size() ?: 0
            if (resetCount > 0) Text("Удалённый сброс параметров пока не применяется ($resetCount).")
            val groupCount = document.getAsJsonArray("groups")?.size() ?: 0
            if (groupCount > 0) {
                Text("Групп в подписке: $groupCount. Группы обновляются вместе с подпиской; локальные изменения настроек сохраняются. Импорт не запускает тесты. Подключите группу для запуска автовыбора.")
            }
            Button(enabled = !busy && selected.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    try { prefs.applySubscriptionSettings(values.filterKeys { it in selected }); status = "Выбранные настройки применены. Предыдущие значения сохранены для отмены." }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = "Настройки не применены: ${e.javaClass.simpleName}" }
                    finally { busy = false }
                }
            }) { Text("Применить выбранные настройки") }
        }
        TextButton(enabled = !busy, onClick = {
            scope.launch { prefs.undoSubscriptionSettings(); status = "Восстановлены значения перед последним применением, если была сохранена копия." }
        }) { Text("Отменить последнее применение настроек") }
        if (status.isNotBlank()) Text(status)
    }
}
