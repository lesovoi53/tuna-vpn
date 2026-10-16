package com.wireturn.app.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.viewmodel.MainViewModel

/** Obtain platform consent before selecting or starting the requested connection. */
@Composable
internal fun rememberVpnConnectAction(viewModel: MainViewModel, onConnect: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val vpn by viewModel.vpnSettings.collectAsStateWithLifecycle()
    val latestConnect by rememberUpdatedState(onConnect)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }
    return {
        if (pending == null) {
            val consent = if (vpn.enabled) VpnService.prepare(context) else null
            if (consent == null) latestConnect()
            else {
                pending = latestConnect
                launcher.launch(consent)
            }
        }
    }
}
