package com.wireturn.app.data

data class DnsPreset(val id: String, val name: String, val servers: String, val description: String)

object DnsPresets {
    val all = listOf(
        DnsPreset("yandex", "Яндекс · обычный DNS", "77.88.8.8,77.88.8.1", "Базовый режим Яндекса. Без DoH."),
        DnsPreset("skydns", "SkyDNS · обычный DNS", "193.58.251.251", "Российский сервис с фильтрацией. Доступ и фильтры зависят от политики SkyDNS. Без DoH."),
        DnsPreset("google", "Google · обычный DNS", "8.8.8.8,8.8.4.4", "Обычные DNS-запросы, без HTTPS."),
        DnsPreset("cloudflare", "Cloudflare · обычный DNS", "1.1.1.1,1.0.0.1", "Обычные DNS-запросы, без HTTPS."),
        DnsPreset("google-doh", "Google · DoH", "https://8.8.8.8/dns-query,https://8.8.4.4/dns-query", "DNS через HTTPS. IP в адресе позволяет подключиться без предварительного DNS-запроса."),
        DnsPreset("cloudflare-doh", "Cloudflare · DoH", "https://1.1.1.1/dns-query,https://1.0.0.1/dns-query", "DNS через HTTPS. IP в адресе позволяет подключиться без предварительного DNS-запроса.")
    )

    data class Endpoint(val id: String, val name: String, val resolver: com.wireturn.app.domain.SubscriptionPolicyParser.Resolver)
    fun resolver(address: String): com.wireturn.app.domain.SubscriptionPolicyParser.Resolver {
        val transport = when { address.startsWith("https://") -> "DOH"; address.startsWith("tcp://") -> "TCP"; else -> "UDP" }
        val endpoint = address.removePrefix("tcp://").removePrefix("udp://")
        return com.wireturn.app.domain.SubscriptionPolicyParser.Resolver(transport, endpoint, null, RouteAction.DIRECT)
    }
    val endpoints: List<Endpoint> by lazy {
        all.flatMap { preset ->
            val region = when (preset.id) { "yandex", "skydns" -> "Россия"; else -> "Глобальный" }
            preset.servers.split(',').mapIndexed { i, address ->
                Endpoint("${preset.id}-$i", "$region · ${preset.name}" + if (i > 0) " · дополнительный" else "", resolver(address))
            }
        }
    }

    fun normalize(value: String): String = value.split(',', '\n', '\r')
        .map(String::trim).filter(String::isNotEmpty).distinct().joinToString(",")

    fun matching(value: String): DnsPreset? = all.firstOrNull { it.servers == normalize(value) }
}
