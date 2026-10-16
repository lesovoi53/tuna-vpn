package com.wireturn.app.ui.activities.kernel

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.wireturn.app.data.kernel.VpnOptions
import com.wireturn.app.data.kernel.CoreCapabilities
import com.wireturn.app.kernel.NativeConfigCheck
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import com.wireturn.app.kernel.SingBoxConfig
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnEnginePolicy
import com.wireturn.app.data.kernel.VpnEditorDraft
import com.wireturn.app.data.kernel.VlessFingerprintChoices
import com.wireturn.app.domain.VlessFingerprintImport
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.activities.MainActivity
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class VpnConfigActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(); super.onCreate(savedInstanceState)
        setContent {
            val ready by viewModel.isInitialized.collectAsStateWithLifecycle()
            if (!ready) return@setContent
            val theme by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamic by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()
            val client by viewModel.clientConfig.collectAsStateWithLifecycle()
            val xray by viewModel.xrayConfig.collectAsStateWithLifecycle()
            val id = intent.getStringExtra("EXTRA_PROFILE_ID")
            val editing = intent.getBooleanExtra("EXTRA_EDIT_MODE", false)
            val profile = profiles.find { it.id == id }
            val initial = remember {
                intent.getStringExtra("EXTRA_CONFIG_JSON")?.let { runCatching { Gson().fromJson(it, VpnConfig::class.java) }.getOrNull() }
                    ?: (profile?.kernelConfig as? KernelConfig.Vpn)?.config
                    ?: if (editing) (client.kernelConfig as? KernelConfig.Vpn)?.config ?: VpnConfig() else VpnConfig()
            }
            val scope = rememberCoroutineScope()
            var options by remember { mutableStateOf(initial.options ?: VpnOptions()) }
            var manualFingerprint by remember { mutableStateOf(false) }
            var advancedError by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var preview by remember { mutableStateOf("") }
            var showCapabilities by remember { mutableStateOf(false) }
            var uri by remember { mutableStateOf(initial.uri) }
            var sourceVariants by remember { mutableStateOf(initial.sanitize().importedVariants) }
            val legacyEngine = initial.selectedEngine
            var validationError by remember { mutableStateOf("") }
            var draft by remember { mutableStateOf(runCatching { VpnEditorDraft.from(initial.uri) }.getOrNull()) }
            var showSource by remember { mutableStateOf(initial.uri.isBlank()) }
            var showOptional by remember { mutableStateOf(false) }
            var name by remember { mutableStateOf(profile?.name ?: intent.getStringExtra("EXTRA_PROFILE_NAME").orEmpty()) }
            var reveal by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf(false) }
            var keepAddress by rememberSaveable { mutableStateOf(profile?.keepSubscriptionAddress == true) }
            val cfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                scope.launch {
                    // CF selection changes the persisted profile. Do not save the editor's
                    // old address over it when the user returns and enables address preservation.
                    val latest = AppPreferences(this@VpnConfigActivity).profilesFlow.first().find { it.id == id }
                    val fresh = (latest?.kernelConfig as? KernelConfig.Vpn)?.config
                    if (fresh != null) {
                        uri = fresh.uri; options = fresh.options ?: VpnOptions()
                        draft = runCatching { VpnEditorDraft.from(fresh.uri) }.getOrNull()
                        showSource = draft == null
                        name = latest.name; keepAddress = latest.keepSubscriptionAddress
                        manualFingerprint = false; validationError = ""; preview = ""
                    }
                }
            }
            WireturnTheme(themeMode = theme, dynamicColor = dynamic) {
                if (initial.isRawXray) {
                    com.wireturn.app.ui.screens.kernel.RawXrayConfigEditor(initial, name.ifBlank { initial.displayName }, { finish() },
                        checkNative = { config -> NativeConfigCheck.check(this@VpnConfigActivity, config); Unit },
                        onSave = { savedName, config ->
                            val kernel = KernelConfig.Vpn(config)
                            if (editing) {
                                if (id != null) viewModel.updateProfileById(id) { it.copy(name = savedName, kernelConfig = kernel) }
                                if (id == null || id == viewModel.currentProfileId.value)
                                    viewModel.saveClientConfig(client.copy(kernelConfig = kernel).fillDefaults())
                            } else {
                                viewModel.addFullProfile(savedName, ClientConfig(kernelConfig = kernel).fillDefaults(),
                                    xray.copy(enabled = false), WgConfig(), VlessConfig(), onSaved = {
                                        startActivity(Intent(this@VpnConfigActivity, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
                                    })
                            }
                            if (editing) {
                                startActivity(Intent(this@VpnConfigActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
                            }
                        })
                    return@WireturnTheme
                }
                Scaffold(topBar = { AppTopAppBar(title = when(draft?.protocol) {
                    "naive" -> "NaiveProxy"; "snell" -> "Snell"; "hysteria2" -> "Hysteria2"; "vless" -> "VLESS"; "mieru" -> "Mieru"; "awg" -> "AmneziaWG"; "wireguard" -> "WireGuard"; "trusttunnel" -> "TrustTunnel"; else -> draft?.protocol?.uppercase() ?: getString(R.string.vpn_editor_add)
                }, onBack = { finish() }) }) { padding ->
                    Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text(getString(R.string.vpn_editor_name)) }, modifier = Modifier.fillMaxWidth())
                        if (draft == null && uri.isBlank()) {
                            com.wireturn.app.ui.screens.kernel.VkChoice(getString(R.string.vpn_editor_manual), getString(R.string.vpn_editor_choose_protocol),
                                VpnEditorDraft.manualProtocols) {
                                draft = VpnEditorDraft.create(it); showSource = false
                            }
                        }
                        val candidateUri = runCatching { if (showSource) uri else draft?.toUri() ?: uri }.getOrDefault(uri)
                        val candidateProfile = VpnEnginePolicy.apply(VpnConfig(candidateUri, legacyEngine, options, sourceVariants))
                        if (sourceVariants.isNotEmpty() && candidateProfile.protocol in setOf("awg", "wireguard")) {
                            val variants = (listOf(uri) + sourceVariants).distinct()
                            com.wireturn.app.ui.screens.kernel.VkChoice(stringResource(R.string.awg_source_variant), uri,
                                variants.mapIndexed { index, source ->
                                    val mtu = runCatching { com.wireturn.app.data.kernel.AwgUri.parse(source).endpoint["mtu"].asInt }.getOrDefault(1280)
                                    source to stringResource(R.string.awg_variant_number, index + 1, mtu)
                                }, !busy) { selected ->
                                sourceVariants = variants.filter { it != selected }
                                uri = selected; draft = VpnEditorDraft.from(selected); showSource = false
                                validationError = ""; preview = ""
                            }
                            Text(stringResource(R.string.awg_source_variant_note), style = MaterialTheme.typography.bodySmall)
                        }
                        if (profile?.subscriptionId != null) {
                            com.wireturn.app.ui.SwitchRow(
                                label = stringResource(R.string.keep_subscription_address),
                                checked = keepAddress,
                                enabled = !busy,
                                onCheckedChange = { if (!busy) keepAddress = it },
                                supportingText = stringResource(R.string.keep_subscription_address_note)
                            )
                        }
                        if (com.wireturn.app.domain.CloudflareProfile.tagged(name)) {
                            OutlinedButton(onClick = {
                                if (profile == null || profile.name != name || profile.keepSubscriptionAddress != keepAddress ||
                                    !com.wireturn.app.domain.CloudflareProfile.equivalent((profile.kernelConfig as? KernelConfig.Vpn)?.config, candidateProfile)) {
                                    validationError = getString(R.string.cf_save_first)
                                } else cfLauncher.launch(Intent(this@VpnConfigActivity, com.wireturn.app.ui.activities.CloudflareIpActivity::class.java)
                                    .putExtra("EXTRA_PROFILE_ID", profile.id))
                            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cf_find)) }
                        }
                        if (candidateProfile.protocol == "snell") {
                            com.wireturn.app.ui.SwitchRow(
                                label = stringResource(R.string.snell_allow_experimental_tfo),
                                checked = options.allowExperimentalSnellTfo,
                                onCheckedChange = {
                                    options = options.copy(allowExperimentalSnellTfo = it)
                                    validationError = ""; preview = ""
                                },
                                supportingText = stringResource(R.string.snell_tfo_compatibility_note)
                            )
                            if (SingBoxConfig.snellTfoSuppressed(candidateProfile)) {
                                Text(stringResource(R.string.snell_tfo_suppressed), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (candidateUri.isNotBlank()) {
                            Text(getString(R.string.vpn_editor_core, candidateProfile.engineLabel), style = MaterialTheme.typography.bodySmall)
                            if (candidateProfile.protocol == "naive") {
                                com.wireturn.app.ui.SwitchRow(
                                    label = stringResource(R.string.vpn_editor_naive_uot),
                                    checked = com.wireturn.app.data.kernel.NaiveUdpOverTcp.enabled(candidateProfile),
                                    enabled = !busy,
                                    onCheckedChange = {
                                        options = com.wireturn.app.data.kernel.NaiveUdpOverTcp.withEnabled(candidateProfile, it)
                                        validationError = ""; preview = ""
                                    },
                                    supportingText = stringResource(R.string.vpn_editor_naive_tcp)
                                )
                            }
                            VpnEnginePolicy.note(candidateProfile)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val compatibility = runCatching { candidateProfile.validate() }.exceptionOrNull()?.message
                        if (!candidateUri.isBlank() && compatibility != null) Text(compatibility, color = MaterialTheme.colorScheme.error)
                        VlessFingerprintChoices.warning(candidateProfile)?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { showCapabilities = !showCapabilities }) { Text(getString(R.string.vpn_editor_capabilities)) }
                        if (showCapabilities) Text(CoreCapabilities.report(), style = MaterialTheme.typography.bodySmall)
                        draft?.let { current ->
                            Text(getString(R.string.vpn_editor_settings), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { showOptional = !showOptional }) {
                                Text(stringResource(if (showOptional) R.string.protocol_hide_unset else R.string.protocol_show_unset))
                            }
                            val isAwgEditor = current.protocol in setOf("awg", "wireguard")
                            val visibleFields = current.fields.filter { showOptional || !it.key.startsWith("add:") }.let {
                                if (isAwgEditor) it.sortedWith(compareBy(::awgFieldSection, ::awgFieldOrder)) else it
                            }
                            visibleFields.forEachIndexed { index, field ->
                                if (isAwgEditor && (index == 0 || awgFieldSection(visibleFields[index - 1]) != awgFieldSection(field))) {
                                    awgEditorSections.getOrNull(awgFieldSection(field))?.let {
                                        Text(stringResource(it.first), style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                val fingerprint = current.protocol in setOf("vless", "trojan", "anytls", "trusttunnel") && field.queryName == "fp"
                                val fieldLabel = advancedProtocolFieldLabel(current.protocol, field)
                                val choices = if (fingerprint) VlessFingerprintChoices.supported(CoreCapabilities.resolve(candidateProfile)) else field.options
                                if (choices.isNotEmpty()) {
                                    com.wireturn.app.ui.screens.kernel.VkChoice(fieldLabel, field.value,
                                        (if (fingerprint && field.value.isEmpty()) listOf("") + choices else choices).map { it to when(it) { "" -> stringResource(if (fingerprint) R.string.advanced_option_imported_default else R.string.advanced_option_default); "0" -> stringResource(R.string.advanced_option_default_zero); "true" -> stringResource(R.string.advanced_option_enabled); "false" -> stringResource(R.string.advanced_option_disabled); else -> if (fingerprint) VlessFingerprintChoices.label(CoreCapabilities.resolve(candidateProfile), it) else it } }, !showSource) {
                                        if (fingerprint) manualFingerprint = true
                                        draft = draft?.change(field.key, it); error = false
                                    }
                                } else {
                                OutlinedTextField(field.value, { value -> draft = draft?.change(field.key, value); error = false },
                                    label = { Text(fieldLabel) }, modifier = Modifier.fillMaxWidth(), enabled = !showSource,
                                    singleLine = field.queryName !in setOf("extra", "certificate", "peers", "extra-headers", "headers", "fm"),
                                    visualTransformation = if (field.secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None)
                                }
                            }
                            if (current.protocol == "hysteria2") Text(getString(R.string.vpn_editor_hopping))
                        }
                        com.wireturn.app.ui.screens.VpnAdvancedEditor(candidateProfile, reveal, { options = it; manualFingerprint = true; validationError = ""; preview = "" }, { advancedError = it })
                        TextButton(enabled = !busy && !advancedError, onClick = { scope.launch {
                            busy = true
                            val checked = runCatching { NativeConfigCheck.check(this@VpnConfigActivity, candidateProfile) }
                            validationError = checked.fold({ it }, { it.message ?: getString(R.string.vpn_editor_check_error) })
                            busy = false
                        } }) { Text(if (busy) getString(R.string.vpn_editor_checking) else getString(R.string.vpn_editor_check)) }
                        TextButton(enabled = !busy && !advancedError && reveal, onClick = { scope.launch {
                            busy = true
                            runCatching { NativeConfigCheck.check(this@VpnConfigActivity, candidateProfile, true) }
                                .onSuccess { preview = it }.onFailure { validationError = it.message ?: getString(R.string.vpn_editor_config_error) }
                            busy = false
                        } }) { Text(getString(R.string.vpn_editor_preview)) }
                        if (reveal && preview.isNotBlank()) {
                            Text(getString(R.string.vpn_editor_preview_note), style = MaterialTheme.typography.bodySmall)
                            Text(preview, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) getString(R.string.vpn_editor_hide_credentials) else getString(R.string.vpn_editor_show_credentials)) }
                        TextButton(onClick = {
                            if (!showSource) uri = runCatching { draft?.toUri() }.getOrNull() ?: uri
                            showSource = !showSource
                        }) { Text(if (showSource) getString(R.string.vpn_editor_hide_source) else getString(R.string.vpn_editor_source)) }
                        if (showSource) {
                            OutlinedTextField(uri, { uri = it; error = false }, label = { Text(getString(R.string.vpn_editor_link)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error,
                                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation())
                            Text(getString(R.string.vpn_editor_parse_note))
                            TextButton(onClick = {
                                val parsed = VpnConfig.parse(uri)?.let { VlessFingerprintImport.vpn(VpnEnginePolicy.apply(it)) }
                                if (parsed == null) error = true else {
                                    draft = VpnEditorDraft.from(parsed.uri); uri = parsed.uri; error = false; showSource = false
                                    if (name.isBlank()) name = parsed.displayName
                                }
                            }) { Text(getString(R.string.vpn_editor_parse)) }
                        }
                        if (error) Text(if (uri.trim().startsWith("vless:", true))
                            getString(R.string.vpn_editor_vless_error)
                        else getString(R.string.vpn_editor_link_error), color = MaterialTheme.colorScheme.error)
                        if (validationError.isNotBlank()) Text(validationError, color = MaterialTheme.colorScheme.error)
                        Text(getString(R.string.vpn_editor_core_note), style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy && !advancedError, onClick = { scope.launch {
                            busy = true
                            val candidate = runCatching { if (showSource) uri else draft?.toUri() ?: uri }.getOrNull()
                            val savedOptions = options
                            val result = runCatching {
                                requireNotNull(candidate) { getString(R.string.vpn_editor_fields_error) }
                                val assigned = VpnEnginePolicy.apply(
                                    (VpnConfig.parse(candidate) ?: VpnConfig(candidate)).copy(engine = legacyEngine, options = savedOptions,
                                        importedVariants = sourceVariants.filter { source -> runCatching {
                                            com.wireturn.app.data.kernel.AwgUri.accountIdentity(source) ==
                                                com.wireturn.app.data.kernel.AwgUri.accountIdentity(candidate)
                                        }.getOrDefault(false) })
                                )
                                VpnEnginePolicy.apply(VlessFingerprintImport.forEditor(assigned, manualFingerprint || !showSource)).also { it.validate() }
                            }
                            val config = result.getOrNull()
                            validationError = result.exceptionOrNull()?.message.orEmpty()
                            if (config == null) { error = true; busy = false; return@launch }
                            val nativeCheck = runCatching { NativeConfigCheck.check(this@VpnConfigActivity, config) }
                            busy = false
                            if (nativeCheck.isFailure) { validationError = nativeCheck.exceptionOrNull()?.message.orEmpty(); return@launch }
                            if (candidate != runCatching { if (showSource) uri else draft?.toUri() ?: uri }.getOrNull() || options != savedOptions) {
                                validationError = getString(R.string.vpn_editor_changed); return@launch
                            }
                            val kc = KernelConfig.Vpn(config)
                            if (editing) {
                                if (id != null) viewModel.updateProfileById(id) { it.copy(name = name.ifBlank { config.displayName }, kernelConfig = kc,
                                    keepSubscriptionAddress = it.subscriptionId != null && keepAddress) }
                                if (id == null || id == viewModel.currentProfileId.value) {
                                    viewModel.saveClientConfig(client.copy(kernelConfig = kc).fillDefaults())
                                }
                            } else {
                                viewModel.addFullProfile(name.ifBlank { config.displayName }, ClientConfig(kernelConfig = kc).fillDefaults(), xray.copy(enabled = false), WgConfig(), VlessConfig(), onSaved = {
                                    startActivity(Intent(this@VpnConfigActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                    finish()
                                })
                                return@launch
                            }
                            startActivity(Intent(this@VpnConfigActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            finish()
                        } }) { Text(getString(R.string.vpn_editor_save)) }
                    }
                }
            }
        }
    }
}
