package com.wireturn.app.vk

import android.content.Context
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig

/** Resolve account-generated qWDTT hashes for this run without rewriting the saved profile. */
class VkRuntime(private val context: Context) {
    suspend fun prepare(cfg: ClientConfig): ClientConfig = when (val k = cfg.kernelConfig) {
        is KernelConfig.Qwdtt -> {
            if (k.config.accountAuth && k.config.hashList.isEmpty()) {
                check(com.wdtt.client.VkAuthWebViewManager.hasVkSessionCookie()) {
                    "Сначала войдите в аккаунт VK в настройках qWDTT."
                }
                com.wireturn.app.AppLogsState.addLog("[qWDTT] Получение хеша звонка через аккаунт VK…")
                val hashes = com.wdtt.client.VkCallHashGenerator.generateHashes(context, 1).getOrThrow()
                val prepared = k.config.copy(vkHashes = hashes.joinToString(","))
                check(prepared.isImportable() && prepared.hashList.isNotEmpty()) {
                    "VK не вернул пригодный хеш звонка. Повторите вход VK."
                }
                cfg.copy(kernelConfig = KernelConfig.Qwdtt(prepared))
            } else cfg
        }
        else -> cfg
    }
}
