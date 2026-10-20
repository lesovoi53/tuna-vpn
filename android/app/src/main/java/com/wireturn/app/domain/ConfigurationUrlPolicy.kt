package com.wireturn.app.domain

import java.net.URL

internal class ConfigurationUrlRejected : IllegalArgumentException(ConfigurationUrlPolicy.HTTPS_REQUIRED)

/** Credential-bearing management downloads require TLS, including LAN and loopback. */
internal object ConfigurationUrlPolicy {
    const val HTTPS_REQUIRED = "Для загрузки конфигурации требуется HTTPS, включая локальную сеть. Сохранённые профили не изменены."
    fun validate(url: URL) {
        if (url.protocol != "https") throw ConfigurationUrlRejected()
        require(url.host.isNotBlank() && url.userInfo == null && url.ref == null) {
            "Недопустимый URL конфигурации"
        }
    }
    fun redirect(from: URL, location: String): URL = URL(from, location).also {
        validate(it)
        require(from.protocol != "https" || it.protocol == "https") { "Перенаправление HTTPS на HTTP запрещено" }
    }

    fun rejectedStatus() = ImportStatus.Rejected(com.wireturn.app.data.SubscriptionImportReport(rejected = listOf(
        com.wireturn.app.data.SubscriptionImportIssue("Загрузка конфигурации", "HTTPS", HTTPS_REQUIRED))))
}
