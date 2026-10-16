package com.wireturn.app.domain

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.RouteAction
import java.net.URI

/** Independent of subscription ownership and of the source of routing rules. */
data class SplitDnsSettings(
    val remote: SubscriptionPolicyParser.Resolver = SubscriptionPolicyParser.Resolver("DOH", "https://8.8.8.8/dns-query", "8.8.8.8", RouteAction.VPN),
    val domestic: SubscriptionPolicyParser.Resolver = SubscriptionPolicyParser.Resolver("UDP", "77.88.8.8", null, RouteAction.DIRECT),
    val hosts: Map<String, String>? = null,
    val queryStrategy: String = "UseIP",
    val disableCache: Boolean = false,
    val remoteServers: List<SubscriptionPolicyParser.Resolver>? = null,
    val domesticServers: List<SubscriptionPolicyParser.Resolver>? = null,
    val remoteSelectionMode: String = "auto",
    val domesticSelectionMode: String = "auto",
    val timeoutMs: Int = 5000,
) {
    val effectiveRemoteServers get() = remoteServers ?: listOf(remote)
    val effectiveDomesticServers get() = domesticServers ?: listOf(domestic)
    fun validate() {
        hosts?.let { StaticHosts.validate(it) }
        require(queryStrategy in setOf("UseIP", "UseIPv4", "UseIPv6")) { "Неизвестный режим DNS A/AAAA" }
        require(remoteSelectionMode in setOf("auto", "priority") && domesticSelectionMode in setOf("auto", "priority")) { "Неизвестный режим выбора DNS" }
        require(timeoutMs in 1000..10000) { "Общий тайм-аут DNS: 1000–10000 мс" }
        for ((servers, route) in listOf(effectiveRemoteServers to RouteAction.VPN, effectiveDomesticServers to RouteAction.DIRECT)) {
            require(servers.size in 1..8) { "В каждой группе требуется от 1 до 8 DNS-серверов" }
            require(servers.map { it.transport to it.endpoint.lowercase().trimEnd('/') }.distinct().size == servers.size) { "Повтор DNS-сервера" }
            require(servers.all { it.route == route }) { "Маршрут DNS не соответствует группе" }
        }
        for (r in effectiveRemoteServers + effectiveDomesticServers) {
            require(r.transport in setOf("DOH", "DOT", "UDP", "TCP")) { "Выберите DoH, DoT, UDP или TCP" }
            val uri = URI(if (r.transport in setOf("DOH", "DOT")) r.endpoint else "dns://${r.endpoint}")
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) { "Некорректный адрес DNS" }
            val host = uri.host.removeSurrounding("[", "]")
            if (r.transport in setOf("DOH", "DOT")) {
                require(uri.scheme == if (r.transport == "DOT") "tls" else "https") { "Нужен https:// для DoH или tls:// для DoT" }
                if (r.transport == "DOT") require(uri.path.isNullOrEmpty() && uri.query == null) { "DoT: tls://сервер:порт без пути" }
                // DoH endpoint names use a separate bootstrap resolver on the same
                // route as their pool. DoT retains the explicit-IP requirement.
                if (r.transport == "DOT") require(isIp(host) || (!r.bootstrapIp.isNullOrBlank() && isIp(r.bootstrapIp))) { "Для DoT с доменом укажите Bootstrap IP" }
            } else {
                require(isIp(host) && uri.path.isNullOrEmpty() && uri.query == null) { "Для UDP/TCP нужен IP, при необходимости с портом" }
            }
            require(r.bootstrapIp.isNullOrBlank() || isIp(r.bootstrapIp)) { "Некорректный Bootstrap IP" }
            if (r.transport in setOf("DOH", "DOT") && !isIp(host) && !r.bootstrapIp.isNullOrBlank()) {
                require(hosts?.get(host) == null || hosts[host] == r.bootstrapIp) { "Локальная запись $host конфликтует с Bootstrap IP" }
            }
        }
    }

    fun validateForRouter(router: com.wireturn.app.data.NetworkRouter) {
        validate()
        require(router != com.wireturn.app.data.NetworkRouter.XRAY || (effectiveRemoteServers + effectiveDomesticServers).none { it.transport == "DOT" }) { "DoT доступен общему extended; для Xray выберите DoH/TCP/UDP" }
    }

    fun toJson(): String = Gson().toJson(this)

    companion object {
        private fun isIp(s: String): Boolean = if (s.contains(':')) {
            s.matches(Regex("[0-9a-fA-F:]+")) && runCatching { java.net.InetAddress.getByName(s) is java.net.Inet6Address }.getOrDefault(false)
        } else s.split('.').let { parts -> parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 } }

        fun fromPolicy(p: SubscriptionPolicyParser.HappPolicy) = p.dns ?: SplitDnsSettings(p.remote, p.domestic, p.hosts)

        fun parse(json: String, fallback: SplitDnsSettings = SplitDnsSettings()): SplitDnsSettings {
            val root = JsonParser.parseString(json).asJsonObject
            require(root.keySet().all { it in setOf("remote", "domestic", "hosts", "queryStrategy", "disableCache", "remoteServers", "domesticServers", "remoteSelectionMode", "domesticSelectionMode", "timeoutMs") }) { "Неизвестное поле DNS" }
            fun resolver(r: JsonObject, route: RouteAction): SubscriptionPolicyParser.Resolver {
                require(r.keySet().all { it in setOf("transport", "endpoint", "bootstrapIp", "route") }) { "Неизвестное поле DNS-сервера" }
                require(r["route"] == null || r["route"].asString == route.name) { "Маршрут DNS не соответствует группе" }
                return SubscriptionPolicyParser.Resolver(r["transport"].asString, r["endpoint"].asString,
                    r["bootstrapIp"]?.takeUnless { it.isJsonNull }?.asString, route)
            }
            fun pool(key: String, route: RouteAction) = root[key]?.let { value ->
                require(value.isJsonArray && value.asJsonArray.size() in 1..8) { "В каждой группе требуется от 1 до 8 DNS-серверов" }
                value.asJsonArray.map { resolver(it.asJsonObject, route) }
            }
            val remote = pool("remoteServers", RouteAction.VPN)
            val domestic = pool("domesticServers", RouteAction.DIRECT)
            val hosts = if (root.has("hosts")) root.get("hosts")?.takeUnless { it.isJsonNull }?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } else fallback.hosts
            val timeout = root["timeoutMs"]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString().matches(Regex("[0-9]+"))); it.asInt } ?: fallback.timeoutMs
            return SplitDnsSettings(remote?.firstOrNull() ?: root.getAsJsonObject("remote")?.let { resolver(it, RouteAction.VPN) } ?: fallback.remote,
                domestic?.firstOrNull() ?: root.getAsJsonObject("domestic")?.let { resolver(it, RouteAction.DIRECT) } ?: fallback.domestic, hosts,
                root["queryStrategy"]?.asString ?: fallback.queryStrategy, root["disableCache"]?.asBoolean ?: fallback.disableCache,
                remote ?: fallback.remoteServers, domestic ?: fallback.domesticServers,
                root["remoteSelectionMode"]?.asString ?: fallback.remoteSelectionMode,
                root["domesticSelectionMode"]?.asString ?: fallback.domesticSelectionMode, timeout).also { it.validate() }
        }

        fun applyToPolicy(policy: String, settings: String): String {
            if (settings.isBlank()) return policy
            val root = JsonParser.parseString(policy).asJsonObject
            if (settings == "disabled") { root.remove("splitDns"); return root.toString() }
            val parsed = parse(settings)
            val dns = root.getAsJsonObject("splitDns") ?: JsonObject()
            dns.addProperty("queryStrategy", parsed.queryStrategy)
            dns.addProperty("disableCache", parsed.disableCache)
            dns.add("remote", Gson().toJsonTree(parsed.remote))
            dns.add("domestic", Gson().toJsonTree(parsed.domestic))
            if (parsed.remoteServers != null) dns.add("remoteServers", Gson().toJsonTree(parsed.remoteServers)) else dns.remove("remoteServers")
            if (parsed.domesticServers != null) dns.add("domesticServers", Gson().toJsonTree(parsed.domesticServers)) else dns.remove("domesticServers")
            dns.addProperty("remoteSelectionMode", parsed.remoteSelectionMode)
            dns.addProperty("domesticSelectionMode", parsed.domesticSelectionMode)
            dns.addProperty("timeoutMs", parsed.timeoutMs)
            parsed.hosts?.let { dns.add("hosts", Gson().toJsonTree(it)) }
            root.add("splitDns", dns)
            return root.toString()
        }
    }
}
