package com.wireturn.app.domain

import com.wireturn.app.data.*
import com.wireturn.app.kernel.SingBoxNetworkCompiler
import com.google.gson.JsonParser
import java.io.File
import java.net.InetAddress

/** Offline explanation. Never resolves a hostname or claims to observe a live connection. */
object RouteExplanation {
    fun explain(settings: SharedNetworkSettings, input: String, assets: File): String {
        val target = input.trim().lowercase().removeSuffix(".")
        require(target.isNotBlank() && '/' !in target && RoutingPolicy.validMatch(target) &&
            (':' !in target || target.matches(Regex("[0-9a-f:]+")))) { "Введите домен или IP без URL и порта" }
        val ip = if (':' in target || target.all { it.isDigit() || it == '.' }) InetAddress.getByName(target).address else null
        val resolved = NetworkPolicyResolver.resolve(settings)
        val base = if (resolved.subscription != null) SubscriptionRoutingAssets.nativePolicy(settings.routing.subscriptionSource)
            else resolved.routing.toJson()
        val root = JsonParser.parseString(resolved.applyOverrides(base)).asJsonObject
        val policy = RoutingPolicy.load(root.toString(), "", "")
        fun cidr(value: String): Boolean {
            if (ip == null) return false
            val network = InetAddress.getByName(value.substringBefore('/')).address
            if (network.size != ip.size) return false
            val bits = value.substringAfter('/', (network.size * 8).toString()).toInt()
            return (0 until bits).all { bit ->
                ((network[bit / 8].toInt() xor ip[bit / 8].toInt()) and (128 ushr (bit % 8))) == 0
            }
        }
        fun domain(kind: String, value: String): Boolean = ip == null && when (kind) {
            "domain" -> target == value.lowercase()
            "domain_suffix" -> target == value.lowercase() || target.endsWith(".${value.lowercase()}")
            "domain_keyword" -> target.contains(value.lowercase())
            else -> error("Регулярное выражение требует проверки самим ядром; локальный прогноз остановлен")
        }
        for ((index, rule) in policy.activeRules().withIndex()) {
            if (ip == null && SingBoxNetworkCompiler.ipMatch(rule.match) && root["domainStrategy"]?.asString == "IPOnDemand") {
                return "До правила ${index + 1} требуется DNS-разрешение IPOnDemand. Без сетевого запроса итог не определён."
            }
            val matched = when {
                rule.match.startsWith("geoip:") || rule.match.startsWith("geosite:") -> {
                    val geo = GeoRuleSets.load(assets, listOf(rule.match)).getValue(rule.match)
                    geo.entrySet().any { (kind, values) -> values.asJsonArray.any {
                        if (kind == "ip_cidr") cidr(it.asString) else domain(kind, it.asString)
                    } }
                }
                SingBoxNetworkCompiler.ipMatch(rule.match) -> cidr(rule.match)
                rule.match.startsWith("full:") -> domain("domain", rule.match.substringAfter(':'))
                rule.match.startsWith("domain:") -> domain("domain_suffix", rule.match.substringAfter(':'))
                rule.match.startsWith("regexp:") -> domain("domain_regex", rule.match.substringAfter(':'))
                else -> domain("domain_keyword", rule.match.removePrefix("keyword:"))
            }
            if (matched) return "Правило ${index + 1}: ${rule.match} → ${rule.action.title}."
        }
        return "Совпадений нет. Основной маршрут: ${policy.fallback}."
    }
}
