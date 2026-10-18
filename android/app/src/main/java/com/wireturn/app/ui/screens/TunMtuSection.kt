package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.domain.TunMtuPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TunMtuSection() {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val saved by preferences.tunMtuFlow.collectAsStateWithLifecycle<Int?>(initialValue = null)
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Card(onClick = { saved?.let { text = it.toString(); failed = false; editing = true } }, enabled = saved != null, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.vpn_tun_mtu_title), style = MaterialTheme.typography.titleMedium)
            saved?.let { Text(stringResource(R.string.vpn_tun_mtu_value, it)) }
            Text(stringResource(R.string.vpn_tun_mtu_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editing) {
        val value = TunMtuPolicy.parse(text)
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text(stringResource(R.string.vpn_tun_mtu_title)) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.vpn_tun_mtu_hint))
                    OutlinedTextField(text, { text = it; failed = false },
                        label = { Text(stringResource(R.string.vpn_tun_mtu_bytes)) },
                        singleLine = true, enabled = !saving, isError = value == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(stringResource(R.string.vpn_tun_mtu_range, TunMtuPolicy.MIN, TunMtuPolicy.MAX)) })
                    Slider(value = TunMtuPolicy.sliderFraction(value ?: saved ?: TunMtuPolicy.DEFAULT),
                        onValueChange = { text = TunMtuPolicy.sliderValue(it).toString(); failed = false },
                        enabled = !saving)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(TunMtuPolicy.MIN.toString()); Text(TunMtuPolicy.MAX.toString())
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TunMtuPolicy.presets.forEach { preset ->
                            FilterChip(selected = value == preset, enabled = !saving,
                                onClick = { text = preset.toString(); failed = false }, label = { Text(preset.toString()) })
                        }
                    }
                    if (failed) Text(stringResource(R.string.vpn_tun_mtu_save_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = value != null && !saving, onClick = {
                    val chosen = value ?: return@TextButton
                    saving = true
                    scope.launch {
                        try { preferences.setTunMtu(chosen); editing = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { saving = false }
                    }
                }) { Text(stringResource(R.string.vpn_tun_mtu_save)) }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { editing = false }) { Text(stringResource(R.string.vpn_tun_mtu_cancel)) } }
        )
    }
}
