package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.DnsPresets
import com.wireturn.app.domain.DnsAvailability
import com.wireturn.app.domain.SubscriptionPolicyParser
import com.wireturn.app.ui.screens.kernel.VkChoice
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
fun DnsPresetPicker(resolver: SubscriptionPolicyParser.Resolver, enabled: Boolean,
                    onChange: (SubscriptionPolicyParser.Resolver) -> Unit, throughTunnel: Boolean = false) {
    val entries = remember { DnsPresets.endpoints }
    val selected = entries.firstOrNull { it.resolver.endpoint == resolver.endpoint && it.resolver.transport == resolver.transport }
    var custom by remember(resolver) { mutableStateOf(selected == null) }
    var showAll by remember { mutableStateOf(false) }
    VkChoice("Готовый DNS", if (custom) "custom" else selected?.id ?: "custom",
        entries.map { it.id to it.name } + ("custom" to "Свой DNS — ввести ниже"), enabled = enabled) { id ->
        custom = id == "custom"
        entries.find { it.id == id }?.let { onChange(it.resolver.copy(route = resolver.route)) }
    }
    DnsCheckButton(listOf(resolver), enabled, throughTunnel)
    TextButton(onClick = { showAll = true }, enabled = enabled) { Text("Проверить доступные варианты") }
    if (showAll) {
        val context = LocalContext.current
        val results = remember { mutableStateMapOf<String, String>() }
        LaunchedEffect(Unit) {
            val limit = Semaphore(4)
            entries.map { entry -> async {
                    limit.withPermit { results[entry.id] = DnsAvailability.check(context, entry.resolver, throughTunnel) }
            } }.awaitAll()
        }
        AlertDialog(onDismissRequest = { showAll = false }, title = { Text("Доступность DNS · ${results.size}/${entries.size}") },
            text = {
                Column {
                    Text(if (throughTunnel) "Через SOCKS активного транспорта VPN. Прямое соединение при ошибке не используется."
                        else "Через Wi-Fi/мобильную сеть, без VPN. Проверяем DNS-ответ для example.com.")
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(entries, key = { it.id }) { entry ->
                            TextButton(onClick = { onChange(entry.resolver.copy(route = resolver.route)); showAll = false }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(entry.name)
                                    Text(entry.resolver.endpoint, style = MaterialTheme.typography.bodySmall)
                                    Text(results[entry.id] ?: "Проверяется…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showAll = false }) { Text("Закрыть") } })
    }
}

@Composable
fun DnsCheckButton(resolvers: List<SubscriptionPolicyParser.Resolver>, enabled: Boolean, throughTunnel: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember(resolvers) { mutableStateOf("") }
    TextButton(enabled = enabled && !busy && resolvers.isNotEmpty(), onClick = {
        busy = true
        scope.launch {
            try {
                result = ""
                val lines = mutableListOf<String>()
                for (r in resolvers.take(8)) lines += "${r.endpoint}: ${DnsAvailability.check(context, r, throughTunnel)}"
                result = lines.joinToString("\n")
            } finally { busy = false }
        }
    }) { Text(if (busy) "Проверяется…" else if (throughTunnel) "Проверить DNS через VPN" else "Проверить DNS без VPN") }
    if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    Text(if (throughTunnel) "Через SOCKS активного транспорта. Цепочка прокси не участвует; прямого обхода при ошибке нет."
        else "Через сеть устройства без VPN. Задержка DNS-запроса, не ICMP-пинг.", style = MaterialTheme.typography.bodySmall)
}
