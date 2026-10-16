package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wireturn.app.data.SubscriptionImportIssue

@Composable
internal fun SubscriptionImportIssuesDialog(
    entries: List<SubscriptionImportIssue>,
    duplicates: Boolean = false,
    onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (duplicates) "Объединены: ${entries.size}" else "Не добавлены: ${entries.size}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(entries) { _, entry ->
                    Column {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text("${entry.protocol} · ${entry.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
