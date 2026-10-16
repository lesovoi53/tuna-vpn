package com.wireturn.app.data

import com.google.gson.Gson

enum class RoutingMode(val title: String) {
    ALL_VPN("Всё через VPN"), SELECTED_VPN("Выбранные сайты через VPN"), SELECTED_DIRECT("Выбранные сайты напрямую")
}
enum class RouteAction(val title: String) { VPN("VPN"), DIRECT("Напрямую"), BLOCK("Блокировать") }
data class RoutingRule(val match: String, val action: RouteAction, val enabled: Boolean = true)
data class RoutingPolicy(val mode: RoutingMode = RoutingMode.SELECTED_DIRECT, val rules: List<RoutingRule> = emptyList(), val geoSource: String = "", val sniffing: Boolean = true) {
    val fallback: String get() = if (mode == RoutingMode.SELECTED_VPN) "Напрямую" else "VPN"
    fun activeRules(): List<RoutingRule> = rules.filter { it.enabled && (mode != RoutingMode.ALL_VPN || it.action == RouteAction.BLOCK) }
    fun validate() {
        require(rules.size <= 500) { "Не более 500 правил" }
        rules.forEach { require(validMatch(it.match)) { "Некорректное правило: ${it.match.take(80)}" } }
    }
    fun warnings(): List<String> {
        val active = activeRules()
        return active.mapIndexedNotNull { i, rule ->
            val earlier = active.take(i).firstOrNull { previous ->
                previous.match.equals(rule.match, true) ||
                    (previous.match.startsWith("domain:") && rule.match.startsWith("domain:") && rule.match.removePrefix("domain:").endsWith("." + previous.match.removePrefix("domain:"), true))
            }
            earlier?.let { "Правило ${i + 1} перекрывается более ранним «${it.match}» (${it.action.title})" }
        }.distinct()
    }
    fun toJson(): String { validate(); return Gson().toJson(this) }
    companion object {
        fun load(json: String, direct: String, block: String): RoutingPolicy {
            if (json.isNotBlank()) return Gson().fromJson(json, RoutingPolicy::class.java).also { it.validate() }
            fun entries(value: String, action: RouteAction) = value.split(',', '\n', '\r').map(String::trim).filter(String::isNotBlank).map { RoutingRule(it, action) }
            return RoutingPolicy(rules = entries(block, RouteAction.BLOCK) + entries(direct, RouteAction.DIRECT))
        }
        fun validMatch(value: String): Boolean {
            if (value.isBlank() || value.length > 500 || value.any { it == '\n' || it == '\r' }) return false
            if (value.startsWith("regexp:")) return com.wireturn.app.domain.RoutingRegex.isValid(value.substringAfter(':'))
            if (value.any { it.isWhitespace() || it == ',' }) return false
            if (value.startsWith("geosite:") || value.startsWith("geoip:")) return value.substringAfter(':').matches(Regex("[a-zA-Z0-9_@!.:-]+"))
            if (value.startsWith("keyword:")) return value.substringAfter(':').isNotEmpty()
            val v = value.removePrefix("domain:").removePrefix("full:")
            if (v.contains(':')) return v.matches(Regex("[0-9a-fA-F:]+(/[0-9]{1,3})?")) &&
                (v.substringAfter('/', "128").toIntOrNull() ?: 999) <= 128 &&
                runCatching { java.net.InetAddress.getByName(v.substringBefore('/')) is java.net.Inet6Address }.getOrDefault(false)
            if (v.contains('/')) return v.substringBefore('/').split('.').let { it.size == 4 && it.all { n -> n.toIntOrNull() in 0..255 } } && v.substringAfter('/').toIntOrNull() in 0..32
            if (v.all { it.isDigit() || it == '.' }) return v.split('.').let { it.size == 4 && it.all { n -> n.toIntOrNull() in 0..255 } }
            return v.split('.').all { it.isNotEmpty() && it.length <= 63 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        }
    }
}

data class RoutingPreset(val title: String, val description: String, val policy: RoutingPolicy)
object RoutingPresets {
    private fun rules(action: RouteAction, vararg names: String) = names.map { RoutingRule(it, action) }
    // Independently authored selections; not copies of the unlicensed Happ configuration.
    val all = listOf(
        RoutingPreset("Весь интернет", "Все сайты через VPN. Пользовательские исключения можно добавить в другом режиме.", RoutingPolicy(RoutingMode.ALL_VPN)),
        RoutingPreset("Выбранные сервисы", "YouTube, Telegram и GitHub через VPN; остальное напрямую. Требуются базы RoscomVPN.", RoutingPolicy(RoutingMode.SELECTED_VPN, rules(RouteAction.VPN, "geosite:youtube", "geosite:telegram", "geosite:github"), "roscomvpn")),
        RoutingPreset("Российские сервисы напрямую", "Российские домены и белый список напрямую; остальные сайты через VPN. Требуются базы RoscomVPN.", RoutingPolicy(RoutingMode.SELECTED_DIRECT, rules(RouteAction.DIRECT, "geosite:category-ru", "geosite:whitelist"), "roscomvpn")),
        RoutingPreset("Только белый список напрямую", "Домены белого списка напрямую; остальное через VPN. Требуются базы RoscomVPN.", RoutingPolicy(RoutingMode.SELECTED_DIRECT, rules(RouteAction.DIRECT, "geosite:whitelist"), "roscomvpn"))
    )
    fun description(name: String): String = when (name.substringAfter(':').substringBefore('@')) {
        "private" -> "Локальные адреса / домены"
        "category-ru" -> "Российские домены и сервисы"
        "whitelist" -> "Белый список сервисов"
        "direct" -> "Диапазоны для прямого доступа из выбранной базы"
        "category-ads", "category-ads-all" -> "Реклама и рекламные сети"
        "win-spy" -> "Телеметрия Windows"
        "torrent" -> "Публичные BitTorrent-сервисы"
        "google-play" -> "Google Play и связанные сервисы Android"
        else -> "Сервисы категории ${name.substringAfter(':')} из установленной базы"
    }
}
