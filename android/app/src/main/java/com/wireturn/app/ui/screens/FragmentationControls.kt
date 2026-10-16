package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.kernel.VpnFragmentation
import com.wireturn.app.ui.screens.kernel.VkChoice
import kotlinx.coroutines.launch

@Composable
fun FragmentationControls(value: VpnFragmentation?, inheritAllowed: Boolean, onChange: (VpnFragmentation?) -> Unit,
                          onInvalid: (Boolean) -> Unit = {}) {
    var length by remember(value?.length) { mutableStateOf(value?.length ?: "100-200") }
    var interval by remember(value?.interval) { mutableStateOf(value?.interval ?: "0-1") }
    val mode = value?.mode ?: if (inheritAllowed) "inherit" else "off"
    val candidate = VpnFragmentation(mode.takeUnless { it == "inherit" } ?: "off", length, interval)
    val failure = if (mode in setOf("inherit", "off")) null else runCatching { candidate.validate() }.exceptionOrNull()?.message
    LaunchedEffect(failure) { onInvalid(failure != null) }
    VkChoice("Режим фрагментации", mode,
        (if (inheritAllowed) listOf("inherit" to "Из ссылки / общих настроек") else emptyList()) +
            VpnFragmentation.modes.map { it to VpnFragmentation.label(it) }) { selected ->
        val next = candidate.copy(mode = selected.takeUnless { it == "inherit" } ?: "off")
        if (selected == "inherit") onChange(null)
        else onChange(if (runCatching { next.validate() }.isSuccess) next else VpnFragmentation(selected))
    }
    if (mode !in setOf("inherit", "off")) {
        OutlinedTextField(length, { text ->
            length = text
            candidate.copy(length = text).let { if (runCatching { it.validate() }.isSuccess) onChange(it) }
        }, label = { Text("Размер фрагмента, байт (число или диапазон)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            isError = failure != null)
        if (mode != "tls") OutlinedTextField(interval, { text ->
            interval = text
            candidate.copy(interval = text).let { if (runCatching { it.validate() }.isSuccess) onChange(it) }
        }, label = { Text("Задержка, мс (число или диапазон)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            isError = failure != null)
        Text(if (mode == "tls") "TLS-записи разделяются, но отправляются одной записью в TCP."
            else "Ядро делает отдельные записи в TCP; окончательные сетевые пакеты определяет ОС.", style = MaterialTheme.typography.bodySmall)
    }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun DefaultFragmentationSection() {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val settings by preferences.defaultFragmentationFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Фрагментация по умолчанию", style = MaterialTheme.typography.titleMedium)
            Text("Для совместимых VLESS TLS/REALITY через Xray. Настройки профиля и ссылки имеют приоритет. Изменение применяется и к тестам; активное совместимое подключение будет перезапущено.",
                style = MaterialTheme.typography.bodySmall)
            FragmentationControls(settings, false, { next -> scope.launch {
                runCatching { preferences.setDefaultFragmentation(next) }.onSuccess { error = "" }
                    .onFailure { error = "Не удалось сохранить настройку" }
            } })
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
