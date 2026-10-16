package com.wireturn.app.ui.screens.kernel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.ui.AppTopAppBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Full-config editing is deliberately separate from URI forms: no flattening or lost outbounds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawXrayConfigEditor(initial: VpnConfig, initialName: String, onBack: () -> Unit,
    checkNative: suspend (VpnConfig) -> Unit, onSave: (String, VpnConfig) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial.rawXrayJson.orEmpty()) }
    var name by remember(initial) { mutableStateOf(initialName) }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun check(save: Boolean) {
        scope.launch {
            busy = true; status = "Проверка конфигурации Xray…"; failed = false
            val draftText = text
            try {
                val config = initial.copy(rawXrayJson = draftText).sanitize().also { it.validate() }
                checkNative(config)
                if (draftText != text) {
                    failed = true; status = "JSON изменился во время проверки. Проверьте ещё раз."
                } else if (save) onSave(name.ifBlank { config.displayName }, config)
                else status = "Конфигурация принята ядром. Доступность серверов ещё не проверена."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Native parser messages may contain credentials or whole JSON fragments.
                failed = true; status = "Конфигурация не прошла проверку. Проверьте JSON, outbounds, routing и ссылки на теги. Исходный профиль не изменён."
            } finally { busy = false }
        }
    }
    Scaffold(topBar = { AppTopAppBar(title = if (initial.isXrayBalancer) "Балансировщик Xray" else "Xray JSON", onBack = onBack) }) { insets ->
        Column(Modifier.padding(insets).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("Полная конфигурация Xray", style = MaterialTheme.typography.titleMedium)
            Text("Outbounds, routing и balancers сохраняются вместе. Это не отдельная VLESS-ссылка. При запуске приложение создаёт свой служебный SOCKS-вход; исходный JSON сохраняется.",
                style = MaterialTheme.typography.bodyMedium)
            Text("JSON может содержать UUID, пароли и ключи. Показывайте его только в доверенной обстановке.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !busy, onClick = { reveal = !reveal }) { Text(if (reveal) "Скрыть JSON" else "Показать и редактировать JSON") }
            if (reveal) OutlinedTextField(text, { text = it; status = ""; failed = false },
                label = { Text("Полный JSON Xray") }, enabled = !busy, isError = failed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 520.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
            else Text("JSON скрыт · ${text.length} символов", style = MaterialTheme.typography.bodySmall)
            if (status.isNotBlank()) Text(status, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(enabled = !busy, onClick = { check(false) }, modifier = Modifier.fillMaxWidth()) { Text("Проверить ядром Xray") }
            Button(enabled = !busy, onClick = { check(true) }, modifier = Modifier.fillMaxWidth()) { Text("Проверить и сохранить") }
        }
    }
}
