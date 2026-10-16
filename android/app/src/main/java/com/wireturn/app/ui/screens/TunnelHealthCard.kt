package com.wireturn.app.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.domain.InternetAccessMonitor
import com.wireturn.app.domain.InternetAccessStatus

/** Uses the same background verdict as the foreground-service notification. */
@Composable
fun TunnelHealthCard(active: Boolean, config: ClientConfig) {
    val status by InternetAccessMonitor.status.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { InternetAccessMonitor.start(context) }
    if (active) {
        Text(stringResource(status.label), style = MaterialTheme.typography.bodySmall)
        if (status == InternetAccessStatus.UNCONFIRMED) {
            TextButton(onClick = { InternetAccessMonitor.retry() }) {
                Text(stringResource(com.wireturn.app.R.string.internet_access_retry))
            }
        }
    }
}
