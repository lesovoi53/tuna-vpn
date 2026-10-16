package com.wireturn.app.ui.screens.kernel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.showExclusiveToast
import kotlinx.coroutines.launch

@Composable
fun VkChoice(title: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean = true, onInfo: ((String) -> Unit)? = null, disabledValues: Set<String> = emptySet(), onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            if (onInfo != null) TextButton(onClick = { onInfo(selected) }, modifier = Modifier.semantics { contentDescription = "Описание: $title" }) { Text("?") }
        }
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }) { Text(options.firstOrNull { it.first == selected }?.second ?: selected) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) -> DropdownMenuItem(enabled = enabled && value !in disabledValues, text = { Text(label) }, onClick = { expanded = false; onSelect(value) },
                    trailingIcon = if (onInfo == null) null else {{
                        TextButton(onClick = { expanded = false; onInfo(value) }, modifier = Modifier.semantics { contentDescription = "Описание: $label" }) { Text("?") }
                    }}) }
            }
        }
    }
}
}

@Composable
fun VkAccountControls(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun refreshAccount() {
        loggedIn = com.wdtt.client.VkAuthWebViewManager.hasVkSessionCookie()
    }
    DisposableEffect(lifecycleOwner) {
        refreshAccount()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshAccount() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column {
        Text(when (loggedIn) { true -> "VK: вход выполнен"; false -> "VK: вход не выполнен"; null -> "VK: проверка входа…" },
            style = MaterialTheme.typography.titleMedium,
            color = if (loggedIn == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (loggedIn == true) Text("Сессия VK сохранена на этом устройстве")
        Text("Аккаунт VK хранится только на этом устройстве и не включается в ссылки профилей.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = enabled && !busy, onClick = {
                busy = true; scope.launch {
                    try {
                        com.wdtt.client.VkAuthWebViewManager.loginOnly(context).fold(
                            onSuccess = { context.showExclusiveToast("Вход VK выполнен") },
                            onFailure = { context.showExclusiveToast("Вход VK не завершён") })
                    } finally { busy = false; refreshAccount() }
                }
            }) { Text(if (busy) "Ожидание VK…" else if (loggedIn == true) "Войти заново" else "Войти в VK") }
            TextButton(enabled = enabled && !busy, onClick = {
                com.wdtt.client.VkAuthWebViewManager.clearVkAuthCookies()
                loggedIn = false
                context.showExclusiveToast("Сессия VK удалена с устройства. Перезапустите активный туннель.")
            }) { Text("Выйти из VK") }
        }
    }
}

@Composable
fun VkHashFields(hashes: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    val fields = hashes.ifEmpty { listOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEachIndexed { index, value ->
            TextFieldRow(label = "VK-хеш ${index + 1}", value = if (enabled) value else "••••••", readOnly = !enabled,
                onValueChange = { text -> if (enabled) onChange(fields.toMutableList().also { it[index] = qwdttHashInput(text) }) })
            if (enabled && fields.size > 1) TextButton(onClick = { onChange(fields.filterIndexed { i, _ -> i != index }) }) { Text("Удалить хеш ${index + 1}") }
        }
        if (enabled && fields.size < 6) TextButton(onClick = { onChange(fields + "") }) { Text("Добавить хеш") }
    }
}

// Accept the documented VK call URL as a convenience; otherwise keep the raw hash.
internal fun qwdttHashInput(input: String): String {
    val value = input.trim()
    val link = runCatching { java.net.URI(value) }.getOrNull() ?: return value
    if (link.scheme !in setOf("http", "https") ||
        link.host?.lowercase() !in setOf("vk.com", "vk.ru", "m.vk.com", "m.vk.ru")) return value
    val parts = link.path.orEmpty().split('/').filter(String::isNotEmpty)
    return if (parts.size == 3 && parts[0] == "call" && parts[1] == "join") parts[2] else value
}

@Composable
fun QwdttGenerateHashes(enabled: Boolean, onGenerated: (List<String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf("1") }
    VkChoice("Количество новых хешей", count, (1..6).map { it.toString() to it.toString() }, enabled && !busy) { count = it }
    TextButton(enabled = enabled && !busy, onClick = {
        busy = true
        scope.launch {
            try {
                val result = com.wdtt.client.VkCallHashGenerator.generateHashes(context, count.toInt())
                result.fold(onSuccess = { onGenerated(it); context.showExclusiveToast("Хеши созданы. Сохраните профиль.") },
                    onFailure = { context.showExclusiveToast("Не удалось создать хеши. Проверьте вход VK и сеть.") })
            } finally { busy = false }
        }
    }) { Text(if (busy) "Создание хешей…" else "Создать хеши VK") }
}
