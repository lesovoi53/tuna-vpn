package com.wireturn.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.domain.ProfileSection

@Composable internal fun profileSectionTitle(section: ProfileSection): String = when (section) {
    ProfileSection.FAVORITES -> stringResource(R.string.server_sections_favorites)
    ProfileSection.DNS -> stringResource(R.string.server_sections_dns)
    ProfileSection.OTHER -> stringResource(R.string.server_sections_other)
    else -> section.title
}

@Composable internal fun ProfileSectionHeader(title: String, count: Int, expanded: Boolean,
    selected: Boolean = false, favorite: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(18.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(if (favorite) R.drawable.profile_star_24px else R.drawable.lan_24px), null,
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(pluralStringResource(R.plurals.server_sections_count, count, count) +
                    if (selected) " · " + stringResource(R.string.server_sections_selected) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}
