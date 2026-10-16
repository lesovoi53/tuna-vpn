package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.kernel.*
import com.wireturn.app.ui.screens.kernel.VkChoice

@Composable
fun VpnAdvancedEditor(profile: VpnConfig, reveal: Boolean, onChange: (VpnOptions) -> Unit, onError: (Boolean) -> Unit) {
    val engine = CoreCapabilities.resolve(profile)
    if (engine == "mieru") return
    val options = profile.options ?: VpnOptions()
    val patch = options.forEngine(engine)
    var expanded by remember { mutableStateOf(false) }
    var section by remember(engine) { mutableStateOf("") }
    var jsonMode by remember(engine) { mutableStateOf(false) }
    val errors = remember(engine) { mutableStateMapOf<String, String>() }
    LaunchedEffect(engine, errors.toMap()) { onError(errors.isNotEmpty()) }
    if (profile.protocol == "vless") {
        Text("Фрагментация этого подключения", style = MaterialTheme.typography.titleMedium)
        FragmentationControls(options.fragmentation, true, { onChange(options.copy(fragmentation = it)) }, {
            if (it) errors["fragment"] = "Проверьте размер и задержку фрагментации" else errors.remove("fragment")
        })
        VpnFragmentation.compatibilityReason(profile)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error) }
        if (options.fragmentation == null) {
            val imported = runCatching { VpnFragmentation.fromUri(profile.uri) }.getOrNull()
            if (imported != null) Text("Из ссылки: ${VpnFragmentation.label(imported.mode)}, ${imported.length} байт, ${imported.interval} мс",
                style = MaterialTheme.typography.bodySmall)
            else Text("Используются общие настройки; нативный FinalMask, если задан, имеет приоритет.", style = MaterialTheme.typography.bodySmall)
        }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.vpn_editor_hide_core_settings else R.string.vpn_editor_core_settings)) }
    if (!expanded) return
    if (patch.has("realm")) Text(stringResource(R.string.core_editor_realm), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.core_editor_description, if (engine == "xray") "Xray" else "sing-box-extended"), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { jsonMode = !jsonMode; errors.clear() }) { Text(stringResource(if (jsonMode) R.string.core_editor_fields else R.string.core_editor_json)) }
    if (jsonMode) {
        var text by remember(engine) { mutableStateOf(patch.toString()) }
        val invalidJson = stringResource(R.string.core_editor_invalid_json)
        OutlinedTextField(text, { text = it; runCatching {
            val parsed = NativeOptions.parse(it)
            NativeOptions.validatePatch(engine, parsed, profile.protocol)
            onChange(options.withEngine(engine, parsed)); errors.remove("json")
        }.onFailure { errors["json"] = it.message ?: invalidJson } },
            label = { Text(stringResource(R.string.core_editor_json_label)) }, modifier = Modifier.fillMaxWidth(), minLines = 4,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation())
        Text(stringResource(R.string.core_editor_json_help), style = MaterialTheme.typography.bodySmall)
    } else {
        CoreCapabilities.fields(engine, profile.protocol).filter {
            !VlessFingerprintChoices.isFingerprintField(profile.protocol, it.path) || it.path == VlessFingerprintChoices.nativePath(profile)
        }.groupBy { it.group }.forEach { (group, fields) ->
            TextButton(onClick = { section = if (section == group) "" else group }) { Text("${if (section == group) "−" else "+"} ${localizedCoreText(group)}") }
            if (section == group) fields.forEach { field ->
                val value = NativeOptions.get(patch, field.path)
                val canonical = if (value == null) "" else if (value.isJsonPrimitive) value.asString else value.toString()
                var text by remember(engine, field.path, canonical) { mutableStateOf(canonical) }
                val label = localizedCoreText(field.label)
                val invalidValue = stringResource(R.string.core_editor_invalid_value, label)
                val update: (String) -> Unit = { input ->
                    text = input
                    runCatching {
                        val parsed = field.parse(input)
                        onChange(options.withEngine(engine, NativeOptions.put(patch, field.path, parsed)))
                        errors.remove(field.path)
                    }.onFailure { errors[field.path] = invalidValue }
                }
                val fingerprint = VlessFingerprintChoices.isFingerprintField(profile.protocol, field.path)
                val choices = if (fingerprint) VlessFingerprintChoices.supported(engine)
                    else if (field.kind == "bool") listOf("true", "false") else field.choices
                if (choices.isNotEmpty()) VkChoice(label, text, (listOf("") + choices).map {
                    it to when (it) { "" -> stringResource(if (fingerprint) R.string.core_editor_from_link else R.string.core_editor_default); "true" -> stringResource(R.string.core_editor_on); "false" -> stringResource(R.string.core_editor_off); else -> if (fingerprint) VlessFingerprintChoices.label(engine, it) else it }
                }, onSelect = update)
                else OutlinedTextField(text, update, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
                    singleLine = field.kind != "json", isError = errors.containsKey(field.path),
                    visualTransformation = if (reveal || (field.kind != "json" && !field.path.contains("password", true) && !field.path.contains("client_key") && !field.path.endsWith("token"))) VisualTransformation.None else PasswordVisualTransformation())
                if (field.hint.isNotEmpty()) Text(localizedCoreText(field.hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    errors.values.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
}
