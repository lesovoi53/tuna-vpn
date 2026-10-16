package com.wireturn.app.ui.activities.kernel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
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
import com.wireturn.app.domain.MasterDnsResolverCheck
import com.wireturn.app.domain.MasterDnsResolverResult
import com.wireturn.app.ui.activities.MainActivity
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.*

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
            val results = remember { mutableStateListOf<MasterDnsResolverResult>() }
            var selectedResolvers by remember { mutableStateOf(setOf<String>()) }
            var scanJob by remember { mutableStateOf<Job?>(null) }
            var scanning by remember { mutableStateOf(false) }
            var scannedConfig by remember { mutableStateOf<MasterDnsConfig?>(null) }
            var progress by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            val importResolvers = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if(uri!=null) scope.launch {
                    try {
                        val text=withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { input ->
                                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                                while(true) { val n=input.read(buffer);if(n<0) break;require(out.size()+n<=65536){"Список слишком большой"};out.write(buffer,0,n) }
                                out.toString("UTF-8")
                            } ?: error("Не удалось прочитать файл")
                        }
                        config=config.copy(resolvers=text);results.clear()
                    } catch(e:Exception) { error=e.message ?: "Ошибка чтения списка" }
                }
            }
            WireturnTheme(themeMode = theme, dynamicColor = dynamic) {
                Scaffold(topBar = { TopAppBar(title = { Text("MasterDnsVPN") }, navigationIcon = { TextButton(onClick = { finish() }) { Text("Назад") } }) }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { OutlinedTextField(name, { name=it }, label={Text("Имя профиля")}, modifier=Modifier.fillMaxWidth()) }
                        item { Text("Самостоятельное подключение · Обход БС. Добавление в группы недоступно.", style=MaterialTheme.typography.bodySmall) }
                        item { TextButton(onClick={importing=!importing}) { Text("Импорт stormdns://") } }
                        if(importing) item {
                            OutlinedTextField(uri,{uri=it},label={Text("Ссылка профиля")},modifier=Modifier.fillMaxWidth(),visualTransformation=PasswordVisualTransformation())
                            TextButton(onClick={
                                val parsed=MasterDnsConfig.parse(uri)
                                if(parsed==null) error="Не удалось импортировать профиль MasterDnsVPN" else { config=parsed; name=parsed.name; importing=false; results.clear(); error=null }
                            }) { Text("Импортировать все параметры") }
                        }
                        item { OutlinedTextField(config.domains.joinToString("\n"),{config=config.copy(domains=it.lines().map(String::trim).filter(String::isNotEmpty));results.clear()}, label={Text("Домены — каждый на своей строке")},modifier=Modifier.fillMaxWidth()) }
                        item {
                            OutlinedTextField(config.key,{config=config.copy(key=it);results.clear()},label={Text("Ключ шифрования")},modifier=Modifier.fillMaxWidth(),visualTransformation=if(reveal) VisualTransformation.None else PasswordVisualTransformation())
                            TextButton(onClick={reveal=!reveal}) { Text(if(reveal) "Скрыть ключ" else "Показать ключ") }
                        }
                        item { TextButton(onClick={encryption=true}) { Text("Шифрование: ${encryptionNames.getOrNull(config.encryption) ?: config.encryption}") } }
                        item { OutlinedTextField(config.resolvers,{config=config.copy(resolvers=it);results.clear()},label={Text("DNS-резолверы / вставить список IP")},supportingText={Text("Один адрес на строку. Допустим IP:порт.")},modifier=Modifier.fillMaxWidth(),minLines=3) }
                        item { TextButton(enabled=!scanning,onClick={importResolvers.launch("text/*")}) { Text("Импорт DNS-резолверов из файла") } }
                        item {
                            Button(enabled=!scanning, onClick={
                                error=null
                                val snapshot=config
                                scannedConfig=snapshot
                                scanJob=scope.launch {
                                    scanning=true;results.clear();selectedResolvers=emptySet();progress="Проверка резолверов и MTU…"
                                    try { MasterDnsResolverCheck.scan(this@MasterDnsConfigActivity,snapshot,{progress=it},{results.add(it);if(it.valid) selectedResolvers=selectedResolvers+it.resolver});progress="Проверка завершена" }
                                    catch(e:CancellationException){progress="Проверка отменена";throw e}
                                    catch(e:Exception){error=e.message ?: "Ошибка проверки"}
                                    finally { scanning=false }
                                }
                            }) { Text("Проверить резолверы") }
                            if(scanning) { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={scanJob?.cancel()}){Text("Отменить проверку")} }
                            if(progress.isNotEmpty()) Text(progress)
                            Text("Проверяется DNS-туннель до вашего сервера и доступный MTU (до 3 минут). Текущий VPN не переключается.",style=MaterialTheme.typography.bodySmall)
                        }
                        items(results.toList()) { result ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(result.resolver in selectedResolvers,{checked -> selectedResolvers=if(checked) selectedResolvers+result.resolver else selectedResolvers-result.resolver},enabled=result.valid && !scanning)
                                Text("${result.resolver} · ${result.domain}\n${if(result.valid) "${result.latencyMs} мс · MTU ↑${result.upload} ↓${result.download}" else "Не прошёл проверку"}")
                            }
                        }
                        if(results.any { it.valid } && !scanning) item { OutlinedButton(enabled=config==scannedConfig && selectedResolvers.isNotEmpty(),onClick={config=config.copy(resolvers=selectedResolvers.joinToString("\n"))}){Text("Использовать выбранные резолверы")} }
                        item { Text("Настройки MasterDnsVPN",style=MaterialTheme.typography.titleLarge); OutlinedTextField(filter,{filter=it},label={Text("Поиск настройки")},modifier=Modifier.fillMaxWidth()) }
                        items(MasterDnsFields.all.filter { filter.isBlank() || it.title.contains(filter,true) || it.key.contains(filter,true) },key={it.key}) { f ->
                            val v=config.value(f)
                            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable {
                                if(f.type=="bool") config=config.copy(settings=config.settings+(f.key to (v!="true").toString()))
                                else { field=f;text=v }
                            }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(f.title); Text(if(f.type=="bool") if(v=="true") "Включено" else "Выключено" else v,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                                if(f.type=="bool") Switch(v=="true",{config=config.copy(settings=config.settings+(f.key to it.toString()))})
                            }
                            HorizontalDivider()
                        }
                        item {
                            if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                            Button(enabled=!scanning,onClick={
                                try {
                                    val candidate=config.copy(name=name.ifBlank { "MasterDnsVPN" });candidate.validate()
                                    val kc=KernelConfig.MasterDns(candidate)
                                    if(editing) {
                                        if(id!=null) vm.updateProfileById(id){it.copy(name=candidate.name,kernelConfig=kc)}
                                        if(id==null || id==vm.currentProfileId.value) vm.saveClientConfig(client.copy(kernelConfig=kc).fillDefaults())
                                    } else vm.addFullProfile(candidate.name,ClientConfig(kernelConfig=kc).fillDefaults(),xray.copy(enabled=false),WgConfig(),VlessConfig())
                                    startActivity(Intent(this@MasterDnsConfigActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP));finish()
                                } catch(e:Exception){error=e.message}
                            },modifier=Modifier.fillMaxWidth()){Text("Сохранить подключение")}
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
                        TextButton(onClick={config=config.copy(encryption=i);encryption=false;results.clear()}) { Text("$i · $label") }
                    } } },
                    confirmButton={TextButton(onClick={encryption=false}) { Text("Закрыть") }}
                )
                if(error!=null && field==null) AlertDialog(onDismissRequest={error=null},title={Text("MasterDnsVPN")},text={Text(error!!)},confirmButton={TextButton(onClick={error=null}){Text("Понятно")}})
            }
        }
    }
    companion object { private val encryptionNames=listOf("Без шифрования","XOR","ChaCha20","AES-128-GCM","AES-192-GCM","AES-256-GCM") }
}
