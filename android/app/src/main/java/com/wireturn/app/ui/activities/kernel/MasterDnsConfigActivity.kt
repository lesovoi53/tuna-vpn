package com.wireturn.app.ui.activities.kernel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import com.wireturn.app.ui.activities.MainActivity
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class MasterDnsConfigActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(); super.onCreate(savedInstanceState)
        setContent {
            val ready by vm.isInitialized.collectAsStateWithLifecycle()
            if (!ready) return@setContent
            val theme by vm.themeMode.collectAsStateWithLifecycle()
            val dynamic by vm.dynamicTheme.collectAsStateWithLifecycle()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val client by vm.clientConfig.collectAsStateWithLifecycle()
            val xray by vm.xrayConfig.collectAsStateWithLifecycle()
            val id = intent.getStringExtra("EXTRA_PROFILE_ID")
            val editing = intent.getBooleanExtra("EXTRA_EDIT_MODE", false)
            val profile = profiles.find { it.id == id }
            var config by remember {
                mutableStateOf(intent.getStringExtra("EXTRA_CONFIG_JSON")?.let { runCatching { Gson().fromJson(it, MasterDnsConfig::class.java) }.getOrNull() }
                    ?: (profile?.kernelConfig as? KernelConfig.MasterDns)?.config
                    ?: if (editing) (client.kernelConfig as? KernelConfig.MasterDns)?.config ?: MasterDnsConfig() else MasterDnsConfig())
            }
            var name by remember { mutableStateOf(profile?.name ?: intent.getStringExtra("EXTRA_PROFILE_NAME")?.takeIf { it.isNotBlank() } ?: config.name) }
            var uri by remember { mutableStateOf("") }
            var importing by remember { mutableStateOf(config.domains.isEmpty()) }
            var reveal by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var field by remember { mutableStateOf<MasterDnsField?>(null) }
            var text by remember { mutableStateOf("") }
            var encryption by remember { mutableStateOf(false) }
            var filter by remember { mutableStateOf("") }
            var showOriginalResolvers by remember { mutableStateOf(false) }
            val save: () -> Unit = {
                try {
                    val candidate = config.copy(name = name.ifBlank { "MasterDnsVPN" }, localPool = null)
                    candidate.validate()
                    val kc = KernelConfig.MasterDns(candidate)
                    if (editing) {
                        if (id != null) vm.updateProfileById(id) { it.copy(name = candidate.name, kernelConfig = kc) }
                        if (id == null || id == vm.currentProfileId.value)
                            vm.saveClientConfig(client.copy(kernelConfig = kc).fillDefaults())
                    } else vm.addFullProfile(candidate.name, ClientConfig(kernelConfig = kc).fillDefaults(),
                        xray.copy(enabled = false), WgConfig(), VlessConfig())
                    startActivity(Intent(this@MasterDnsConfigActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                } catch (e: Exception) { error = e.message }
            }
            WireturnTheme(themeMode = theme, dynamicColor = dynamic) {
                Scaffold(topBar = { TopAppBar(title = { Text("MasterDnsVPN") },
                    navigationIcon = { TextButton(onClick = { finish() }) { Text("Назад") } },
                    actions = { TextButton(onClick = save) { Text("Сохранить") } }) }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { OutlinedTextField(name, { name=it }, label={Text("Имя профиля")}, modifier=Modifier.fillMaxWidth()) }
                        item { Text("DNS-туннель · Обход БС", style=MaterialTheme.typography.bodySmall) }
                        item { TextButton(onClick={importing=!importing}) { Text("Импорт stormdns://") } }
                        if(importing) item {
                            OutlinedTextField(uri,{uri=it},label={Text("Ссылка профиля")},modifier=Modifier.fillMaxWidth(),visualTransformation=PasswordVisualTransformation())
                            TextButton(onClick={
                                val parsed=MasterDnsConfig.parse(uri)
                                if(parsed==null) error="Не удалось импортировать профиль MasterDnsVPN" else { config=parsed; name=parsed.name; importing=false; error=null }
                            }) { Text("Импортировать все параметры") }
                        }
                        item { OutlinedTextField(config.domains.joinToString("\n"),{config=config.copy(domains=it.lines().map(String::trim).filter(String::isNotEmpty))}, label={Text("Домены — каждый на своей строке")},modifier=Modifier.fillMaxWidth()) }
                        item {
                            OutlinedTextField(config.key,{config=config.copy(key=it)},label={Text("Ключ шифрования")},modifier=Modifier.fillMaxWidth(),visualTransformation=if(reveal) VisualTransformation.None else PasswordVisualTransformation())
                            TextButton(onClick={reveal=!reveal}) { Text(if(reveal) "Скрыть ключ" else "Показать ключ") }
                        }
                        item { TextButton(onClick={encryption=true}) { Text("Шифрование: ${encryptionNames.getOrNull(config.encryption) ?: config.encryption}") } }
                        item {
                            val local = config.useLocalResolvers == true
                            val activeResolvers = config.resolverList()
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("DNS-серверы подключения", style = MaterialTheme.typography.titleMedium)
                                    Text(if (local) "Используется: мой выбранный набор"
                                        else "Используется: список профиля / подписки",
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("Адресов: ${activeResolvers.size}", style = MaterialTheme.typography.bodySmall)
                                    SelectionContainer {
                                        Text(activeResolvers.joinToString("\n").ifEmpty { "Адреса не заданы" },
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (local) Text("Эти адреса выбраны для подключения и сохраняются при обновлении подписки.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (local) TextButton(onClick = { showOriginalResolvers = !showOriginalResolvers }) {
                                Text(if (showOriginalResolvers) "Скрыть исходный список"
                                    else "Показать исходный список — сейчас не используется")
                            }
                            if (!local || showOriginalResolvers) OutlinedTextField(
                                config.resolvers, {config=config.copy(resolvers=it)},
                                label={Text("Исходные DNS-резолверы профиля")},
                                supportingText={Text(if (local) "Сейчас подключение использует выбранный набор выше. Изменение этого поля его не заменит."
                                    else "Один адрес на строку. Допустим IP:порт.")},
                                modifier=Modifier.fillMaxWidth(), minLines=3)
                        }
                        item {
                            if (editing && profile != null) {
                                val saved = (profile.kernelConfig as? KernelConfig.MasterDns)?.config
                                OutlinedButton(enabled = config == saved, onClick = {
                                    startActivity(Intent(this@MasterDnsConfigActivity,
                                        com.wireturn.app.ui.activities.MasterDnsResolversActivity::class.java)
                                        .putExtra("EXTRA_PROFILE_ID", profile.id))
                                    finish()
                                }) { Text("DNS-резолверы: база и сканирование") }
                                if (config != saved) Text("Сначала сохраните изменения профиля.", style = MaterialTheme.typography.bodySmall)
                            } else Text("После сохранения откройте DNS-резолверы в меню профиля: там доступны база и сканирование.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            Text("Режим работы", style = MaterialTheme.typography.titleLarge)
                            val selectedMode = config.performanceMode()
                            val choices = listOf<MasterDnsPerformanceMode?>(null) + MasterDnsPerformanceMode.entries
                            choices.forEach { mode ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    config = config.copy(localPerformanceMode = mode?.wireValue)
                                }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selectedMode == mode, onClick = {
                                        config = config.copy(localPerformanceMode = mode?.wireValue)
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text(mode?.title ?: "Из профиля")
                                        Text(when (mode) {
                                            null -> "Исходные параметры, включая ручные настройки"
                                            MasterDnsPerformanceMode.SPEED -> "Меньше копий пакетов и служебного трафика"
                                            MasterDnsPerformanceMode.BALANCED -> "Умеренное дублирование пакетов"
                                            MasterDnsPerformanceMode.STABLE -> "Больше копий при потерях; выше расход трафика"
                                        }, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            fun requested(key: String) = config.effectiveValue(MasterDnsFields.byKey.getValue(key))
                            Text("Запрашивается: балансировка ${requested("RESOLVER_BALANCING_STRATEGY")}; " +
                                "копий обычного пакета ${requested("PACKET_DUPLICATION_COUNT")}; " +
                                "setup ${requested("SETUP_PACKET_DUPLICATION_COUNT")}. " +
                                "Сервер может ограничить дублирование при согласовании сессии.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Режим сохраняется локально. Для уже работающего подключения потребуется переподключение.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        item { Text("Настройки MasterDnsVPN",style=MaterialTheme.typography.titleLarge); OutlinedTextField(filter,{filter=it},label={Text("Поиск настройки")},modifier=Modifier.fillMaxWidth()) }
                        items(MasterDnsFields.all.filter { filter.isBlank() || it.title.contains(filter,true) || it.key.contains(filter,true) },key={it.key}) { f ->
                            val v=config.value(f)
                            val mode = config.performanceMode()
                            val controlledByMode = mode?.overrides?.containsKey(f.key) == true
                            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(enabled = !controlledByMode) {
                                if(f.type=="bool") config=config.copy(settings=config.settings+(f.key to (v!="true").toString()))
                                else { field=f;text=v }
                            }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.title)
                                    Text(if (controlledByMode) "Запрашивается: ${config.effectiveValue(f)} · В профиле: $v"
                                        else if(f.type=="bool") if(v=="true") "Включено" else "Выключено" else v,
                                        style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (controlledByMode) Text("Задано режимом «${mode!!.title}». Для ручного изменения выберите «Из профиля».",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if(f.type=="bool") Switch(v=="true",{config=config.copy(settings=config.settings+(f.key to it.toString()))})
                            }
                            HorizontalDivider()
                        }
                        item {
                            if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                            Button(onClick=save,modifier=Modifier.fillMaxWidth()){Text("Сохранить подключение")}
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
                if(field!=null) AlertDialog(onDismissRequest={field=null},title={Text(field!!.title)},text={
                    Column {
                        Text(field!!.key,style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(text,{text=it},label={Text("Значение")},modifier=Modifier.fillMaxWidth())
                        Text("По умолчанию: ${field!!.defaultValue}")
                        if(field!!.key=="RESOLVER_BALANCING_STRATEGY") Text("0/2 — по кругу; 1 — случайно; 3 — потери; 4 — задержка; 5 — смешанная оценка; 6 — потери и задержка; 7/8 — лучшие по потерям (случайно/по кругу)")
                        if(field!!.key.endsWith("COMPRESSION_TYPE")) Text("0 — выключено; 1 — ZSTD; 2 — LZ4; 3 — ZLIB")
                        if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                    }
                },confirmButton={TextButton(onClick={try {MasterDnsConfig.validateValue(field!!,text);config=config.copy(settings=config.settings+(field!!.key to text));field=null;error=null}catch(e:Exception){error=e.message}}){Text("Применить")}},dismissButton={TextButton(onClick={config=config.copy(settings=config.settings-field!!.key);field=null}){Text("По умолчанию")}})
                if(encryption) AlertDialog(
                    onDismissRequest={encryption=false}, title={Text("Метод шифрования")},
                    text={ Column { encryptionNames.forEachIndexed { i,label ->
                        TextButton(onClick={config=config.copy(encryption=i);encryption=false}) { Text("$i · $label") }
                    } } },
                    confirmButton={TextButton(onClick={encryption=false}) { Text("Закрыть") }}
                )
                if(error!=null && field==null) AlertDialog(onDismissRequest={error=null},title={Text("MasterDnsVPN")},text={Text(error!!)},confirmButton={TextButton(onClick={error=null}){Text("Понятно")}})
            }
        }
    }
    companion object { private val encryptionNames=listOf("Без шифрования","XOR","ChaCha20","AES-128-GCM","AES-192-GCM","AES-256-GCM") }
}
