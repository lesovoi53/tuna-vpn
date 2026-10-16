package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.*
import com.wireturn.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RouteInspectorSection(policy: RoutingPolicy, source: String, split: SplitDnsSettings?, lan: Boolean, routingEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(policy, source, split, lan, host, ip) { result = "" }
    RoutingSettingsGroup("Проверить маршрут домена", "Правило, действие и выбранный DNS") {
        Text(if (routingEnabled) "Расчёт по настройкам этого экрана. Несохранённые изменения ещё не действуют. Это не сетевой тест."
            else "Маршрутизация выключена. Ниже — только расчёт сохранённых/редактируемых правил.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(host, { host = it }, label = { Text("Домен или IP") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(ip, { ip = it }, label = { Text("IP домена, если известен (необязательно)") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        TextButton(enabled = !busy && host.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try {
                    result = withContext(Dispatchers.IO) { runCatching {
                        val imported = source.takeIf { it.isNotBlank() }?.let(SubscriptionPolicyParser::parseHapp)
                        val effective = RoutingOverrides.withLan(imported?.routing ?: policy, lan)
                        val directory = if (imported == null) GeoAssetsManager.assetsDir(context) else SubscriptionRoutingAssets.directory(context, source)
                        val answer = RouteInspector.inspect(effective, host, ip.ifBlank { split?.hosts?.get(host.trim().lowercase()).orEmpty() }, directory,
                            imported?.domainStrategy ?: "IPIfNonMatch")
                        val dns = when {
                            split == null -> "Общий DNS"
                            answer.dnsAction == RouteAction.DIRECT -> "Напрямую: ${split.domestic.endpoint}"
                            answer.dnsAction == RouteAction.VPN -> "Через VPN: ${split.remote.endpoint}"
                            answer.dnsAction == RouteAction.BLOCK -> "DNS блокируется"
                            else -> "Не определён"
                        }
                        "Источник: ${imported?.name ?: "Локальные правила"}\nМаршрут: ${answer.action?.title ?: "Требуется уточнение"}\nУсловие: ${answer.match ?: "—"}\nDNS: $dns\n${answer.detail}"
                    }.getOrElse { it.message ?: "Не удалось проверить правила" } }
                } finally { busy = false }
            }
        }) { Text(if (busy) "Проверяется…" else "Рассчитать маршрут") }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
}
