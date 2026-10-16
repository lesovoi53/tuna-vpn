package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.DnsPresets
import com.wireturn.app.domain.QwdttDnsSetting

@Composable
fun QwdttDnsChoice(value: String, editable: Boolean, onChange: (String) -> Unit) {
    val addresses = QwdttDnsSetting.addresses(value)
    val resolver = DnsPresets.resolver(addresses.substringBefore(','))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DNS для подключения к VK", style = MaterialTheme.typography.titleMedium)
        Text("Используется qWDTT при получении TURN-доступа. DNS сайтов настраивается в разделе «Маршрутизация и DNS».", style = MaterialTheme.typography.bodySmall)
        DnsPresetPicker(resolver, editable, onChange = { onChange(QwdttDnsSetting.encode(it.endpoint)) })
        OutlinedTextField(addresses, { onChange(QwdttDnsSetting.encode(it)) }, enabled = editable,
            modifier = Modifier.fillMaxWidth(), label = { Text("Свой DNS: IP:порт или https://…") },
            supportingText = { Text("Можно указать несколько адресов одного типа через запятую. qWDTT поддерживает обычный DNS и DoH.") })
        if (addresses.contains(',')) DnsCheckButton(addresses.split(',').filter { it.isNotBlank() }.map(DnsPresets::resolver), editable)
    }
}
