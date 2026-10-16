package com.wdtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.wireturn.app.vk.VkHostApplication
import com.wireturn.app.AppLogsState

internal object MainActivity {
    val currentActivity get() = VkHostApplication.resumed.get()
    val isForeground get() = currentActivity != null
}
internal object SettingsStore { const val MAX_VK_HASHES = 6 }
internal object NotificationHelper {
    fun areNotificationsEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun ensureAuxChannel(context: Context, id: String, name: String, description: String) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply { this.description = description })
    }
}
internal object TunnelManager {
    fun addVkAuthLog(message: String, isError: Boolean, verbose: Boolean) {
        if (isError) AppLogsState.addLog("[VK/qWDTT] Ошибка авторизации. Откройте настройки профиля и повторите вход VK.")
    }
}
/** Accept a bare call identifier or a VK call URL without relying on another backend. */
internal fun stripVkUrlStatic(value: String): String {
    val text = value.trim()
    if (!text.contains("://")) return text
    val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return text
    val path = uri.path.orEmpty().trimEnd('/')
    return if (path.startsWith("/call/")) path.removePrefix("/call/") else text
}
