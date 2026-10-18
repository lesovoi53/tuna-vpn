package com.wireturn.app.domain

import java.net.URL

/** Credential-bearing configuration downloads: HTTPS publicly, literal LAN HTTP retained. */
internal object ConfigurationUrlPolicy {
    fun validate(url: URL) {
        require(url.protocol in setOf("https", "http") && url.host.isNotBlank() && url.userInfo == null && url.ref == null) {
            "Недопустимый URL конфигурации"
        }
        require(url.protocol == "https" || isLocalNetworkHost(url.toString())) { "Для публичной конфигурации требуется HTTPS" }
    }
    fun redirect(from: URL, location: String): URL = URL(from, location).also {
        validate(it)
        require(from.protocol != "https" || it.protocol == "https") { "Перенаправление HTTPS на HTTP запрещено" }
    }
}
