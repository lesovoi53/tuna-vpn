package com.wireturn.app.ui.screens

import android.os.SystemClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wireturn.app.data.RoutingDisableConsent
import com.wireturn.app.ui.SwitchRow
import kotlinx.coroutines.delay

@Composable
fun RoutingControlSection(
    enabled: Boolean,
    onEnable: () -> Unit,
    onDisable: (RoutingDisableConsent) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    val confirmation = remember { RoutingDisableConsent.Confirmation() }
    val cancelFocus = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cancel = { step = 0; ready = false; confirmation.cancel() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); confirmation.cancel() }
    }
    LaunchedEffect(step) {
        ready = false
        if (step == 2) {
            withFrameNanos { }
            cancelFocus.requestFocus()
            delay(650)
            ready = true
        }
    }
    SwitchRow(
        label = "Маршрутизация",
        supportingText = if (enabled) "Включена для всех профилей. Правила VPN, DIRECT и BLOCK, раздельный DNS."
            else "Маршрутизация отключена вами. Нажмите, чтобы включить.",
        checked = enabled,
        onCheckedChange = { if (it) onEnable() else step = 1 },
    )
    if (step == 1) AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Отключить маршрутизацию?") },
        text = { Text("Правила сайтов и раздельный DNS перестанут применяться. VPN продолжит работать через выбранный профиль.") },
        dismissButton = { TextButton(onClick = cancel) { Text("Оставить включённой") } },
        confirmButton = { TextButton(onClick = {
            confirmation.continueToSecondStep(SystemClock.elapsedRealtime()); step = 2
        }) { Text("Продолжить") } },
    )
    if (step == 2) AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Подтвердите отключение") },
        text = { Text("Маршрутизация будет отключена для всех профилей и групп до вашего повторного включения.") },
        dismissButton = { TextButton(modifier = Modifier.focusRequester(cancelFocus), onClick = cancel) { Text("Отмена") } },
        confirmButton = { TextButton(enabled = ready, onClick = {
            confirmation.confirm(SystemClock.elapsedRealtime())?.let { onDisable(it); cancel() }
        }) { Text("Отключить маршрутизацию") } },
    )
}
