package com.wireturn.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.Profile
import com.wireturn.app.domain.ProfileEncoder
import com.wireturn.app.domain.ProfileLinkExport
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.showExclusiveToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileLinkExportMenu(
    profile: Profile,
    expanded: Boolean,
    onDismiss: () -> Unit,
    profileJson: () -> String,
    onSave: () -> Unit,
    onShareTuna: () -> Unit,
    onShareOriginal: (String) -> Unit
) {
    val context = LocalContext.current
    val exportLabel = stringResource(R.string.profile_export)
    val copiedMessage = stringResource(R.string.profile_link_copied)
    val errorMessage = stringResource(R.string.profile_export_error)
    val tooLargeMessage = stringResource(R.string.profile_link_too_large)
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    // Serializers can validate and allocate a large payload; prepare only an open menu, off-main.
    var original by remember(profile) { mutableStateOf<ProfileLinkExport.Original?>(null) }
    androidx.compose.runtime.LaunchedEffect(profile, expanded) {
        if (expanded) original = withContext(Dispatchers.Default) { ProfileLinkExport.original(profile) }
    }
    val link = (original as? ProfileLinkExport.Original.Link)?.uri
    val reason = (original as? ProfileLinkExport.Original.Unavailable)?.reason
    fun copyLink(value: String) {
        val clip = ClipData.newPlainText(exportLabel, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        context.showExclusiveToast(copiedMessage)
    }
    AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        title = stringResource(R.string.profile_export)) {
        Text(stringResource(R.string.profile_link_scope),
            modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall)
        if (reason != null) Text(stringResource(when (reason) {
            ProfileLinkExport.UnavailableReason.UNSUPPORTED -> R.string.profile_link_unavailable
            ProfileLinkExport.UnavailableReason.CUSTOM_SETTINGS -> R.string.profile_link_custom
            ProfileLinkExport.UnavailableReason.LOCAL_POOL -> R.string.profile_link_pool
            ProfileLinkExport.UnavailableReason.INVALID -> R.string.profile_link_invalid
        }), modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        DropdownMenuItem(text = { Text(stringResource(R.string.profile_link_copy_original)) },
            enabled = link != null && !preparing, onClick = {
                link?.let {
                    try { copyLink(it); onDismiss() }
                    catch (_: Exception) { context.showExclusiveToast(errorMessage) }
                }
            })
        DropdownMenuItem(text = { Text(stringResource(R.string.profile_link_share_original)) },
            enabled = link != null && !preparing, onClick = {
                link?.let {
                    try { onShareOriginal(it); onDismiss() }
                    catch (_: Exception) { context.showExclusiveToast(errorMessage) }
                }
            })
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.profile_link_copy_tuna)) },
            enabled = !preparing, onClick = {
                preparing = true
                scope.launch {
                    try {
                        val encoded = withContext(Dispatchers.Default) { ProfileEncoder.encodeForSharing(profileJson()) }
                        if (encoded == null) context.showExclusiveToast(tooLargeMessage)
                        else { copyLink("tuna://$encoded"); onDismiss() }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { context.showExclusiveToast(errorMessage) }
                    finally { preparing = false }
                }
            })
        DropdownMenuItem(text = { Text(stringResource(R.string.profile_link_share_tuna)) },
            enabled = !preparing, onClick = onShareTuna)
        DropdownMenuItem(text = { Text(stringResource(R.string.profile_export_save)) },
            enabled = !preparing, onClick = onSave)
    }
}
