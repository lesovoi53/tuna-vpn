package com.wireturn.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.domain.pollWhileVisible
import kotlinx.coroutines.flow.map

@Composable
fun WorkerStatusCard(config: KernelConfig) {
    val target: Int
    val hashes: Int
    val title: String
    when (config) {
        is KernelConfig.Qwdtt -> { target = config.config.effectiveWorkers; hashes = config.config.hashList.size; title = "qWDTT" }
        else -> return
    }
    val sample by CoreServiceState.workerStats.collectAsStateWithLifecycle()
    val status by CoreServiceState.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle, sample, status) {
        if (sample != null && status !is CoreStatus.Idle) {
            pollWhileVisible(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }) {
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    val stopped = status is CoreStatus.Idle || status is CoreStatus.Stopping || status is CoreStatus.Error
    val count = when {
        stopped -> stringResource(R.string.cd_workers_active, 0, target)
        sample?.fresh(now) == true -> stringResource(R.string.cd_workers_active, requireNotNull(sample!!.active), sample!!.target)
        sample?.active != null -> stringResource(R.string.cd_no_fresh_data)
        else -> stringResource(R.string.cd_workers_wait, sample?.target ?: target)
    }
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cd_workers_status, count), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.cd_saved_hashes, hashes), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.cd_workers_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}
