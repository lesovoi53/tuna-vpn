package com.wireturn.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R

/** Translate the batch's existing status contract at display time, including locale changes. */
@Composable
internal fun localizedProfileProbeLabel(label: String): String {
    val resource = when (label) {
        "Ожидание" -> R.string.profile_probe_waiting
        "Проверка…" -> R.string.profile_probe_running
        "Отменено" -> R.string.profile_probe_cancelled
        "Таймаут" -> R.string.profile_probe_timeout
        "Ошибка URL-теста" -> R.string.profile_probe_failed
        "Аккаунт WG занят другим подключением" -> R.string.profile_probe_active_wg
        else -> null
    }
    if (resource != null) return stringResource(resource)
    val milliseconds = label.takeIf { it.endsWith(" мс") }?.removeSuffix(" мс")?.toLongOrNull()
    return if (milliseconds != null) stringResource(R.string.profile_probe_latency, milliseconds) else label
}
