package com.wireturn.app.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R

@Composable
fun LocalProxyAuthWarning(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_proxy_noauth_title)) },
        text = { Text(stringResource(R.string.local_proxy_noauth_warning)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.local_proxy_noauth_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.local_proxy_keep_auth)) } })
}
