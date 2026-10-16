package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.Subscription
import com.wireturn.app.domain.SubscriptionPolicyParser
import com.wireturn.app.domain.SubscriptionRoutingAssets
import kotlinx.coroutines.launch

@Composable
fun SubscriptionRoutingSection(source: String, subscriptions: List<Subscription>, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val preview = remember(candidate) { runCatching { SubscriptionPolicyParser.parseHapp(candidate) }.getOrNull() }
    val active = remember(source) { runCatching { SubscriptionPolicyParser.parseHapp(source) }.getOrNull() }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Источник маршрутизации", style = MaterialTheme.typography.titleMedium)
        Text("Выбрано: ${routingSourceLabel(source, subscriptions)}", style = MaterialTheme.typography.titleSmall)
        Text("Изменения на этом экране применятся после сохранения и переподключения.", style = MaterialTheme.typography.bodySmall)
        Text("add сохраняет правила без активации. onadd активирует правила и DNS при импорте подписки. Здесь можно вручную выбрать сохранённый набор. Локальные правила сохраняются отдельно.", style = MaterialTheme.typography.bodySmall)
        subscriptions.filter { !it.routingSource.isNullOrBlank() }.forEach { sub ->
            TextButton(enabled = !busy, onClick = { candidate = sub.routingSource.orEmpty(); status = "" }) {
                Text("Просмотреть: ${sub.name}")
            }
        }
        OutlinedTextField(candidate, { candidate = it; status = "" }, label = { Text("Ссылка happ://routing/add/… или onadd/…") }, maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        if (preview != null) {
            Text("${preview.name}\nПравил: ${preview.routing.rules.size}; остальное: ${preview.routing.fallback}\nЧерез VPN (удалённый DNS): ${preview.remote.transport} ${preview.remote.endpoint} → VPN\nНапрямую (прямой DNS): ${preview.domestic.transport} ${preview.domestic.endpoint} → напрямую\nСтатических DNS-записей: ${preview.hosts.size}")
            preview.routing.rules.forEach { Text("${it.action}: ${it.match}", style = MaterialTheme.typography.bodySmall) }
            preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("DNS можно изменить в этой локальной копии. Оригинал подписки сохранится.", style = MaterialTheme.typography.bodySmall)
            for ((prefix, resolver) in listOf("Remote" to preview.remote, "Domestic" to preview.domestic)) {
                var endpoint by remember(candidate, prefix) { mutableStateOf(resolver.endpoint) }
                var bootstrap by remember(candidate, prefix) { mutableStateOf(resolver.bootstrapIp.orEmpty()) }
                var transport by remember(candidate, prefix) { mutableStateOf(resolver.transport) }
                Text(if (prefix == "Remote") "Через VPN · удалённый DNS" else "Напрямую · прямой DNS", color = MaterialTheme.colorScheme.primary)
                Row {
                    listOf("DOH", "UDP", "TCP").forEach { type ->
                        FilterChip(selected = transport == type, enabled = !busy, onClick = { transport = type }, label = { Text(type) })
                        Spacer(Modifier.width(8.dp))
                    }
                }
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Адрес DNS / HTTPS URL") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(bootstrap, { bootstrap = it }, label = { Text("Bootstrap IP для DoH (необязательно)") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                TextButton(enabled = !busy, onClick = {
                    val changed = preview.source.deepCopy()
                    changed.addProperty(prefix + "DNSType", transport)
                    changed.addProperty(prefix + if (transport == "DOH") "DNSDomain" else "Dns", endpoint.trim())
                    changed.addProperty(prefix + "DNSIP", bootstrap.trim())
                    val link = "happ://routing/onadd/" + java.util.Base64.getEncoder().encodeToString(changed.toString().toByteArray(Charsets.UTF_8))
                    if (runCatching { SubscriptionPolicyParser.parseHapp(link) }.isSuccess) { candidate = link; status = "DNS изменён в предпросмотре. Выберите снимок ниже." }
                    else status = "Проверьте DNS-адрес и тип транспорта"
                }) { Text(if (prefix == "Remote") "Обновить DNS через VPN" else "Обновить прямой DNS") }
            }
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        SubscriptionRoutingAssets.prepare(context, candidate)
                        onChange(candidate)
                        status = "Снимок подготовлен. Сохраните настройки. Доступность DNS проверяется при подключении."
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        status = "Не удалось подготовить правила (${e.javaClass.simpleName}). Старые настройки сохранены."
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Загрузка и проверка баз…" else "Выбрать этот снимок") }
        } else if (candidate.isNotBlank()) Text("Некорректная или неподдерживаемая ссылка маршрутизации", color = MaterialTheme.colorScheme.error)
        if (source.isNotBlank()) TextButton(enabled = !busy, onClick = { onChange(""); status = "Выбраны свои правила. Сохраните и переподключите VPN." }) { Text("Выбрать свои правила — без подписки") }
        if (status.isNotBlank()) Text(status)
    }
}
