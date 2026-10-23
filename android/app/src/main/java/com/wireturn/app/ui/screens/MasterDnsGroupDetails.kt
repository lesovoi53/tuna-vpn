package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.CoreServiceState
import com.wireturn.app.data.MasterDnsGroup
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.domain.MasterDnsPoolPlan
import com.wireturn.app.viewmodel.MainViewModel

@Composable
internal fun MasterDnsPlanDetails(plan: MasterDnsPoolPlan, privacy: Boolean) {
    val status by CoreServiceState.statusText.collectAsStateWithLifecycle()
    Text("Группа DNS · ${plan.members.size} серверов", style = MaterialTheme.typography.titleMedium)
    status?.takeIf { it.startsWith("DNS-туннель:") }?.let { Text(it) }
    Text(if (plan.strategy == "round_robin") "Новые соединения: по кругу" else "Новые соединения: меньше активных соединений",
        style = MaterialTheme.typography.bodySmall)
    plan.members.forEach { member ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(member.name, style = MaterialTheme.typography.titleSmall)
                Text(if (privacy) "Адреса скрыты" else member.config.domains.joinToString(", "))
                Text(if (member.config.useLocalResolvers == true) "Локально выбранные резолверы" else "Резолверы профиля",
                    style = MaterialTheme.typography.labelMedium)
                Text(if (privacy) "••••••••" else member.config.resolverList().joinToString("\n"))
            }
        }
    }
    Text("Показан состав запущенной сессии. Ядро сообщает общую готовность группы; индивидуальное состояние участников неизвестно.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
fun MasterDnsGroupDetailsDialog(group: MasterDnsGroup, vm: MainViewModel,
    onDismiss: () -> Unit, onSettings: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val session by CoreServiceState.session.collectAsStateWithLifecycle()
    val privacy by vm.privacyMode.collectAsStateWithLifecycle()
    val plan = session?.clientConfig?.masterDnsPoolPlan?.takeIf { session?.masterDnsGroupId == group.id }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val connect = rememberVpnConnectAction(vm) {
        connecting = true
        vm.connectMasterDnsGroup(group.id, { connecting = false; onDismiss() },
            { error = it; connecting = false })
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge)
                if (plan != null) MasterDnsPlanDetails(plan, privacy)
                else {
                    Text("DNS-туннель · ${group.memberIds.size} серверов")
                    Text(if (group.strategy == "round_robin") "По кругу" else "Меньше активных соединений")
                    group.memberIds.forEach { id ->
                        val profile = profiles.find { it.id == id }
                        val available = profile?.kernelConfig is KernelConfig.MasterDns && profile.subscriptionId == group.subscriptionId
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(profile?.name ?: "Сервер отсутствует в подписке")
                                Text(if (available) "Есть в составе; состояние соединения неизвестно"
                                    else "Недоступен: проверьте состав группы", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = connect, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (connecting) "Подключение…" else "Подключить группу")
                }
                TextButton(onClick = onSettings) { Text("Настроить: Серверы → DNS-туннель") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
}
