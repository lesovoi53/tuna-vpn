package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.XraySettings
import com.wireturn.app.domain.*
import kotlinx.coroutines.*

@Composable
fun RouteExplanationSection(settings: XraySettings, editable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var result by remember(settings, target) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Предварительная проверка правила", style = MaterialTheme.typography.titleMedium)
            Text("Для текущих полей экрана, без сетевых запросов. Не подтверждает реальный путь трафика, DNS-перехват или результат sniffing.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(target, { target = it }, label = { Text("Домен или IP") }, enabled = editable && !busy, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = editable && !busy && target.isNotBlank(), onClick = {
                busy = true
                val snapshot = settings
                val address = target
                scope.launch {
                    try {
                        val answer = withContext(Dispatchers.IO) { runCatching {
                            val source = snapshot.happRoutingSource
                            val assets = if (source.isBlank()) GeoAssetsManager.assetsDir(context)
                                else SubscriptionRoutingAssets.directory(context, source)
                            RouteExplanation.explain(SharedNetworkSettings.fromLegacy(snapshot), address, assets)
                        }.getOrElse { "Прогноз недоступен: ${it.message}" } }
                        if (settings == snapshot && target == address) result = answer
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Проверка…" else "Проверить правило") }
            if (result.isNotBlank()) Text(result)
        }
    }
}
