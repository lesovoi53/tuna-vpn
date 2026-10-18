package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.domain.DnsRecoverySettings
import com.wireturn.app.domain.SubscriptionPolicyParser
import com.wireturn.app.ui.screens.kernel.VkChoice

@Composable
fun SplitDnsSection(enabled: Boolean, settings: SplitDnsSettings, editable: Boolean,
    onEnabled: (Boolean) -> Unit, onChange: (SplitDnsSettings) -> Unit, extended: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Разделять DNS по маршрутам", style = MaterialTheme.typography.titleMedium)
                Text("Для сайтов через VPN и для прямых соединений", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onEnabled, enabled = editable)
        }
        if (enabled) {
            ResolverPool(true, settings.effectiveRemoteServers, settings.remoteSelectionMode, editable, extended,
                { onChange(settings.copy(remoteSelectionMode = it)) }) { onChange(settings.copy(remote = it.first(), remoteServers = it)) }
            DnsRecoveryEditor("Аварийный резерв DNS через VPN", settings.remoteEmergency, true, editable) {
                onChange(settings.copy(remoteEmergency = it))
            }
            ResolverPool(false, settings.effectiveDomesticServers, settings.domesticSelectionMode, editable, extended,
                { onChange(settings.copy(domesticSelectionMode = it)) }) { onChange(settings.copy(domestic = it.first(), domesticServers = it)) }
            DnsRecoveryEditor("Аварийный резерв прямого DNS", settings.domesticEmergency, false, editable) {
                onChange(settings.copy(domesticEmergency = it))
            }
            BootstrapEditor(settings.bootstrap, editable) { onChange(settings.copy(bootstrap = it)) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Общий тайм-аут DNS-запроса: ${settings.timeoutMs / 1000} с")
                    Slider(settings.timeoutMs.toFloat(), { onChange(settings.copy(timeoutMs = it.toInt())) },
                        valueRange = 1000f..10000f, steps = 8, enabled = editable)
                    VkChoice("Запросы IP-адресов", settings.queryStrategy,
                        listOf("UseIP" to "IPv4 и IPv6", "UseIPv4" to "Только IPv4 (A)", "UseIPv6" to "Только IPv6 (AAAA)"),
                        enabled = editable) { onChange(settings.copy(queryStrategy = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Кэш DNS")
                            Text("В памяти ядра, до истечения TTL. Перезапуск очищает кэш.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(!settings.disableCache, { onChange(settings.copy(disableCache = !it)) }, enabled = editable)
                    }
                    Text("Аварийный резерв используется после сетевого отказа всей группы. NXDOMAIN и корректный пустой ответ его не включают. Системный резерв VPN DNS — явное исключение: имена уходят напрямую вне VPN. Выбор A/AAAA не меняет маршруты IPv6 и не относится к bootstrap.", style = MaterialTheme.typography.bodySmall)
                }
            }
            StaticHostsEditor(settings.hosts, editable) { onChange(settings.copy(hosts = it)) }
            Text("Какой DNS использовать, определяют правила маршрутизации сайта. FakeDNS при разделении отключён.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Разделение выключено. Используется общий DNS из блока «Общий DNS» ниже. Сохранённые серверы для двух маршрутов сейчас не применяются.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DnsRecoveryEditor(title: String, settings: DnsRecoverySettings?, throughVpn: Boolean,
    editable: Boolean, onChange: (DnsRecoverySettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VkChoice(title, settings?.mode ?: "disabled", listOf("disabled" to "Отключён",
            "system" to "Системный DNS Android (Localhost)", "resolver" to "Заданный сервер"), enabled = editable) { mode ->
            onChange(DnsRecoverySettings(mode, if (mode == "resolver") settings?.resolver ?: SubscriptionPolicyParser.Resolver(
                "TCP", "", null, if (throughVpn) com.wireturn.app.data.RouteAction.VPN else com.wireturn.app.data.RouteAction.DIRECT) else null))
        }
        if (settings?.mode == "system") Text("DNS физической сети Android, напрямую (DIRECT), вне VPN. Android Private DNS может менять резолвер. Localhost не означает 127.0.0.1:53.",
            style = MaterialTheme.typography.bodySmall)
        settings?.resolver?.let { resolver ->
            ResolverCard(throughVpn, resolver, editable, false) { onChange(settings.copy(resolver = it)) }
        }
        Text("Отдельный аварийный резерв не входит в 1–8 основных серверов и не участвует в фоновых пробах.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BootstrapEditor(settings: DnsRecoverySettings?, editable: Boolean, onChange: (DnsRecoverySettings?) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VkChoice("Bootstrap DNS", settings?.mode ?: "inherit", listOf("inherit" to "Общая настройка",
                "system" to "Системный DNS Android (Localhost)", "resolver" to "TCP DNS с IP-адресом"), enabled = editable) { mode ->
                onChange(if (mode == "inherit") null else DnsRecoverySettings(mode,
                    if (mode == "resolver") settings?.resolver ?: SubscriptionPolicyParser.Resolver("TCP", "", null,
                        com.wireturn.app.data.RouteAction.DIRECT) else null))
            }
            if (settings?.mode == "system") Text("Имя сервера DNS определяется через физическую сеть Android напрямую (DIRECT), вне VPN.",
                style = MaterialTheme.typography.bodySmall)
            settings?.resolver?.let { resolver ->
                OutlinedTextField(resolver.endpoint, { onChange(settings.copy(resolver = resolver.copy(endpoint = it.trim()))) },
                    label = { Text("IP:порт Bootstrap DNS") }, supportingText = { Text("TCP напрямую (DIRECT), вне VPN. Например: 192.0.2.53:53 или [2001:db8::53]:53") },
                    modifier = Modifier.fillMaxWidth(), enabled = editable)
            }
            Text("Указанный у DoH Bootstrap IP имеет приоритет. Пустое поле не заменяется скрытым публичным сервером.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResolverPool(throughVpn: Boolean, servers: List<SubscriptionPolicyParser.Resolver>, mode: String,
    editable: Boolean, extended: Boolean, onMode: (String) -> Unit, onChange: (List<SubscriptionPolicyParser.Resolver>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (throughVpn) "DNS через VPN · ${servers.size}/8" else "DNS напрямую · ${servers.size}/8", style = MaterialTheme.typography.titleMedium)
        VkChoice("Выбор сервера", mode, listOf("auto" to "Авто: быстрый и стабильный", "priority" to "По порядку списка"), enabled = editable, onSelect = onMode)
        Text("Резервные серверы используются только внутри этой группы. В режиме приоритета порядок меняется стрелками.", style = MaterialTheme.typography.bodySmall)
        servers.forEachIndexed { index, resolver ->
            key(index, throughVpn) {
                ResolverCard(throughVpn, resolver, editable, extended) { next -> onChange(servers.toMutableList().apply { set(index, next) }) }
                Row {
                    TextButton(enabled = editable && index > 0, onClick = {
                        onChange(servers.toMutableList().apply { add(index - 1, removeAt(index)) })
                    }) { Text("↑") }
                    TextButton(enabled = editable && index < servers.lastIndex, onClick = {
                        onChange(servers.toMutableList().apply { add(index + 1, removeAt(index)) })
                    }) { Text("↓") }
                    TextButton(enabled = editable && servers.size > 1, onClick = {
                        onChange(servers.toMutableList().apply { removeAt(index) })
                    }) { Text("Удалить DNS ${index + 1}") }
                }
            }
        }
        OutlinedButton(enabled = editable && servers.size < 8, onClick = {
            onChange(servers + SubscriptionPolicyParser.Resolver("UDP", "", null,
                if (throughVpn) com.wireturn.app.data.RouteAction.VPN else com.wireturn.app.data.RouteAction.DIRECT))
        }) { Text("Добавить DNS-сервер") }
    }
}

@Composable
private fun ResolverCard(throughVpn: Boolean, resolver: SubscriptionPolicyParser.Resolver, editable: Boolean, extended: Boolean,
    onChange: (SubscriptionPolicyParser.Resolver) -> Unit) {
    var editing by rememberSaveable(throughVpn) { mutableStateOf(false) }
    val title = if (throughVpn) "Через VPN · удалённый DNS" else "Напрямую · прямой DNS"
    val addressLabel = if (throughVpn) "Адрес DNS через VPN" else "Адрес прямого DNS"
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (throughVpn) R.drawable.vpn_key_24px else R.drawable.route_24px), null,
                    Modifier.padding(end = 12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(if (throughVpn) "Запросы имён для сайтов с маршрутом VPN идут внутри туннеля."
                else "Запросы имён для сайтов с маршрутом DIRECT идут через Wi-Fi или мобильную сеть, вне VPN.",
                style = MaterialTheme.typography.bodySmall)
            Text(resolver.endpoint, style = MaterialTheme.typography.bodyLarge)
            Text(when (resolver.transport) { "DOH" -> "DoH · HTTPS"; else -> resolver.transport },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Свернуть настройки" else "Изменить DNS") }
            if (editing) {
                HorizontalDivider()
                DnsPresetPicker(resolver, editable, onChange, throughTunnel = throughVpn)
                VkChoice("Протокол DNS", resolver.transport,
                    listOf("DOH" to "DoH (HTTPS)", "UDP" to "UDP", "TCP" to "TCP") + (if (extended) listOf("DOT" to "DoT (TLS)") else emptyList()), enabled = editable) {
                    onChange(resolver.copy(transport = it))
                }
                OutlinedTextField(resolver.endpoint, { onChange(resolver.copy(endpoint = it.trim())) },
                    label = { Text(addressLabel) }, supportingText = { Text("IP:порт, https://…/dns-query или tls://…:853") },
                    modifier = Modifier.fillMaxWidth(), enabled = editable)
                if (resolver.transport in setOf("DOH", "DOT")) OutlinedTextField(resolver.bootstrapIp.orEmpty(),
                    { onChange(resolver.copy(bootstrapIp = it.trim().ifBlank { null })) },
                    label = { Text("IP сервера DNS (bootstrap)") },
                    supportingText = { Text(if (resolver.transport == "DOH") "Необязательно. Имя DoH определяется выбранным Bootstrap DNS напрямую (DIRECT), вне VPN."
                        else "Нужен, если в адресе DoT указано доменное имя.") },
                    modifier = Modifier.fillMaxWidth(), enabled = editable)
            }
        }
    }
}
