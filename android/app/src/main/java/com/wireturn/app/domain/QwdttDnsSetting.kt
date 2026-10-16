package com.wireturn.app.domain

import com.wireturn.app.data.DnsPresets

object QwdttDnsSetting {
    fun addresses(value: String): String = when (value) {
        "yandex", "" -> "77.88.8.8,77.88.8.1"
        "cloudflare" -> "1.1.1.1,1.0.0.1"
        "google" -> "8.8.8.8,8.8.4.4"
        "doh-yandex" -> "https://77.88.8.8/dns-query"
        "doh-google" -> "https://8.8.8.8/dns-query"
        "doh-cloudflare" -> "https://1.1.1.1/dns-query"
        else -> value.removePrefix("custom:").removePrefix("doh:")
    }
    fun encode(value: String): String {
        val cleaned = DnsPresets.normalize(value)
        return if (cleaned.startsWith("https://")) "doh:$cleaned" else "custom:$cleaned"
    }
}
