package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.domain.ProtocolGuide
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.SectionItem

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProtocolGuideScreen(onBack: () -> Unit) {
    val links = LocalUriHandler.current
    Scaffold(topBar = { AppTopAppBar(title = stringResource(R.string.protocol_guide_title), onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(stringResource(R.string.protocol_guide_intro), style = MaterialTheme.typography.bodyMedium) }
            ProtocolGuide.entries.groupBy { it.category }.forEach { (category, entries) ->
                item(key = "category_$category") {
                    Text(stringResource(category), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(entries.size, key = { entries[it].id }) { index ->
                    val entry = entries[index]
                    SectionItem(position = ItemPosition.Single) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(entry.description), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { links.openUri(entry.source) }) {
                            Text(stringResource(R.string.protocol_guide_source))
                        }
                    }
                }
            }
        }
    }
}
