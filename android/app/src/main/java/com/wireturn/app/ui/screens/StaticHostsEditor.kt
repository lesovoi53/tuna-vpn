package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.wireturn.app.domain.StaticHosts

@Composable
fun StaticHostsEditor(hosts: Map<String, String>?, editable: Boolean, onChange: (Map<String, String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    TextButton(enabled = editable, onClick = {
        text = hosts.orEmpty().entries.joinToString("\n") { "${it.key} ${it.value}" }; error = ""; open = true
    }) { Text("Записи домен → IP (${hosts?.size ?: 0})") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Локальные DNS-записи") }, text = {
        Column {
            Text("Изменяется локальная копия. Исходная подписка не редактируется. Одна запись в строке: example.com 192.0.2.1")
            OutlinedTextField(text, { text = it; error = "" }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { StaticHosts.parse(text) }.onSuccess { onChange(it); open = false }
            .onFailure { error = it.message ?: "Проверьте записи" }
    }) { Text("Применить") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } })
}
