package com.wireturn.app.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.domain.NetworkPolicyResolver
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.domain.SubscriptionRoutingAssets
import java.net.URI

/**
 * Global extended router for authenticated SOCKS profiles without proxy chains.
 * Android device acceptance is tracked separately. Unsupported policy
 * semantics are errors, not a best-effort conversion or an implicit DIRECT fallback.
 */
object SingBoxNetworkCompiler {
    data class LocalSocks(val address: String, val username: String, val password: String) {
        internal fun endpoint(): URI {
            val uri = URI("socks://$address")
            require(uri.host in setOf("127.0.0.1", "[::1]", "::1") && uri.port in 1..65535 &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) {
                "Общий маршрутизатор требует локального SOCKS-адреса"
            }
            require(username.isNotBlank() && password.isNotBlank()) { "Для локального SOCKS нужна авторизация" }
            require(username.toByteArray(Charsets.UTF_8).size in 1..255 && password.toByteArray(Charsets.UTF_8).size in 1..255) {
                "Логин и пароль SOCKS должны занимать от 1 до 255 байт UTF-8"
            }
            return uri
        }
    }

    fun compile(settings: SharedNetworkSettings, listener: LocalSocks, upstream: LocalSocks,
        geoRules: Map<String, JsonObject> = emptyMap(), hevEnabled: Boolean = true): JsonObject {
        val incoming = listener.endpoint()
        val outgoing = upstream.endpoint()
        require(incoming != outgoing) { "Маршрутизатор не может подключаться к самому себе" }
        require(hevEnabled || !settings.dns.fakeDns) { "FakeDNS ядра extended ещё не подключён; Android mapdns обслуживает HEV" }
        val resolved = NetworkPolicyResolver.resolveForCompilation(settings)
        // Check before Gson can omit null enums from an imported policy.
        require(resolved.routing.mode in RoutingMode.entries) { "Неизвестный режим маршрутизации" }
        resolved.routing.rules.forEach { require(it.action in RouteAction.entries) { "Неизвестное действие правила" } }
        val base = if (resolved.subscription != null) SubscriptionRoutingAssets.nativePolicy(settings.routing.subscriptionSource)
            else resolved.routing.toJson()
        val policy = JsonParser.parseString(resolved.applyOverrides(base)).asJsonObject
        require(policy["domainStrategy"]?.asString in setOf(null, "AsIs", "IPIfNonMatch")) {
            "Для extended ещё не реализована совместимость IPOnDemand"
        }
        require(policy.has("splitDns")) { "Для общего extended задайте отдельные DNS через VPN и напрямую" }
        val dns = SplitDnsSettings.parse(policy["splitDns"].toString())
        require(listOf(dns.remoteEmergency, dns.domesticEmergency).all { it == null || it.mode == "disabled" }) {
            "Аварийный DNS требует общего маршрутизатора Xray; extended пока не поддерживает терминальный резерв"
        }
        val routing = RoutingPolicy.load(policy.toString(), "", "")
        val active = routing.activeRules()
        // Validate every active match before constructing an executable configuration.
        active.forEach { match(it.match, geoRules) }
        val fallback = if (routing.mode == RoutingMode.SELECTED_VPN) "direct" else "vpn"
        return JsonObject().apply {
            add("log", obj { addProperty("level", "info") })
            add("inbounds", array(obj {
                addProperty("type", "socks"); addProperty("tag", "local")
                addProperty("listen", incoming.host.removeSurrounding("[", "]")); addProperty("listen_port", incoming.port)
                add("users", array(obj { addProperty("username", listener.username); addProperty("password", listener.password) }))
            }))
            add("outbounds", array(obj {
                addProperty("type", "socks"); addProperty("tag", "vpn"); addProperty("version", "5")
                addProperty("server", outgoing.host.removeSurrounding("[", "]")); addProperty("server_port", outgoing.port)
                addProperty("username", upstream.username); addProperty("password", upstream.password)
            }, obj {
                addProperty("type", "direct"); addProperty("tag", "direct")
                addProperty("domain_resolver", "dns-direct")
            }))
            add("dns", obj {
                val servers = JsonArray()
                fun pool(items: List<com.wireturn.app.domain.SubscriptionPolicyParser.Resolver>, tag: String, detour: String, mode: String, explicit: Boolean) {
                    val automaticBootstrap = items.any { it.transport == "DOH" && it.bootstrapIp.isNullOrBlank() &&
                        !com.wireturn.app.domain.StaticHosts.isIp(URI(it.endpoint).host.removeSurrounding("[", "]")) && dns.hosts?.get(URI(it.endpoint).host) == null }
                    if (automaticBootstrap) servers.add((BootstrapDns.parse(EffectiveDnsBootstrap.resolve(settings)) ?: BootstrapDns("tcp", "1.1.1.1", 53))
                        .singBoxServer("$tag-bootstrap").apply {
                            if (get("type").asString != "local") addProperty("detour", if (dns.bootstrap == null) detour else "direct")
                        })
                    fun member(item: com.wireturn.app.domain.SubscriptionPolicyParser.Resolver, memberTag: String) =
                        resolver(item, memberTag, detour, "$tag-bootstrap", dns.hosts.orEmpty())
                    // Preserve legacy configurations; explicit pools honor even a singleton timeout.
                    if (items.size == 1 && !explicit && dns.timeoutMs == 5000 && mode == "auto") { servers.add(member(items.single(), tag)); return }
                    items.forEachIndexed { index, item -> servers.add(member(item, "$tag-$index")) }
                    servers.add(obj {
                        addProperty("type", "fallback"); addProperty("tag", tag)
                        add("servers", strings(*items.indices.map { "$tag-$it" }.toTypedArray()))
                        addProperty("strategy", if (mode == "auto") "auto" else "sequential")
                        addProperty("timeout", "${dns.timeoutMs}ms")
                    })
                }
                pool(dns.effectiveRemoteServers, "dns-vpn", "vpn", dns.remoteSelectionMode, dns.remoteServers != null)
                pool(dns.effectiveDomesticServers, "dns-direct", "direct", dns.domesticSelectionMode, dns.domesticServers != null)
                val rules = JsonArray()
                if (!dns.hosts.isNullOrEmpty()) {
                    servers.add(obj {
                        addProperty("type", "hosts"); addProperty("tag", "static-hosts")
                        add("predefined", obj { dns.hosts.forEach { (host, ip) -> add(host, strings(ip)) } })
                    })
                    rules.add(obj {
                        add("domain", strings(*dns.hosts.keys.toTypedArray()))
                        addProperty("action", "route"); addProperty("server", "static-hosts")
                    })
                }
                active.filterNot { ipMatch(it.match) }.forEach { rule -> rules.add(match(rule.match, geoRules).apply {
                    if (rule.action == RouteAction.BLOCK) {
                        addProperty("action", "reject")
                    } else {
                        addProperty("action", "route")
                        addProperty("server", if (rule.action == RouteAction.DIRECT) "dns-direct" else "dns-vpn")
                    }
                }) }
                add("servers", servers); add("rules", rules); addProperty("final", "dns-$fallback")
                addProperty("disable_cache", dns.disableCache)
                when (dns.queryStrategy) {
                    "UseIPv4" -> addProperty("strategy", "ipv4_only")
                    "UseIPv6" -> addProperty("strategy", "ipv6_only")
                }
            })
            add("route", obj {
                add("rule_set", JsonArray().apply {
                    active.map { it.match }.distinct().filter { it.startsWith("geoip:") || it.startsWith("geosite:") }.forEach { key ->
                        add(obj {
                            addProperty("type", "inline"); addProperty("tag", key)
                            add("rules", array(geoRules.getValue(key).deepCopy()))
                        })
                    }
                })
                addProperty("final", fallback)
                addProperty("default_domain_resolver", "dns-$fallback")
                add("rules", JsonArray().apply {
                    add(obj { addProperty("port", 53); addProperty("action", "hijack-dns") })
                    if (routing.sniffing) add(obj {
                        addProperty("action", "sniff"); add("sniffer", strings("http", "tls", "quic")); addProperty("timeout", "300ms")
                    })
                    active.forEach { rule -> add(match(rule.match, geoRules).apply {
                        if (rule.action == RouteAction.BLOCK) addProperty("action", "reject")
                        else {
                            addProperty("action", "route")
                            addProperty("outbound", if (rule.action == RouteAction.DIRECT) "direct" else "vpn")
                        }
                    }) }
                })
            })
        }
    }

    private fun resolver(r: com.wireturn.app.domain.SubscriptionPolicyParser.Resolver, tag: String, detour: String,
        bootstrapTag: String, hosts: Map<String, String>): JsonObject {
        val doh = r.transport == "DOH"
        val tls = doh || r.transport == "DOT"
        val uri = URI(if (tls) r.endpoint else "dns://${r.endpoint}")
        require(!doh || uri.rawQuery == null) { "DoH с query-параметрами пока не поддержан общим extended" }
        val host = uri.host.removeSurrounding("[", "]")
        val ip = r.bootstrapIp?.takeIf(String::isNotBlank) ?: hosts[host] ?: host
        return obj {
            addProperty("type", if (doh) "https" else if (tls) "tls" else r.transport.lowercase())
            addProperty("tag", tag); addProperty("detour", detour)
            addProperty("server", if (tls) ip else host)
            if (doh && !com.wireturn.app.domain.StaticHosts.isIp(ip)) addProperty("domain_resolver", bootstrapTag)
            addProperty("server_port", uri.port.takeIf { it > 0 } ?: if (doh) 443 else if (tls) 853 else 53)
            if (tls) {
                if (doh) addProperty("path", uri.rawPath.ifEmpty { "/dns-query" })
                add("tls", obj { addProperty("enabled", true); addProperty("server_name", host) })
            }
        }
    }

    internal fun ipMatch(value: String): Boolean = value.startsWith("geoip:") ||
        (!value.startsWith("geosite:") && !value.startsWith("domain:") && !value.startsWith("full:") &&
         !value.startsWith("keyword:") && !value.startsWith("regexp:") &&
         (':' in value || '/' in value || value.all { it.isDigit() || it == '.' }))

    private fun match(value: String, geoRules: Map<String, JsonObject>): JsonObject {
        if (value.startsWith("geoip:") || value.startsWith("geosite:")) {
            require(geoRules[value]?.size()?.let { it > 0 } == true) { "Для extended не подготовлена категория $value" }
            return obj { add("rule_set", strings(value)) }
        }
        if (ipMatch(value)) return obj { add("ip_cidr", strings(value)) }

        val (field, text) = when {
            value.startsWith("domain:") -> "domain_suffix" to value.removePrefix("domain:")
            value.startsWith("full:") -> "domain" to value.removePrefix("full:")
            value.startsWith("keyword:") -> "domain_keyword" to value.removePrefix("keyword:")
            value.startsWith("regexp:") -> "domain_regex" to value.removePrefix("regexp:")
            ':' in value || '/' in value || value.all { it.isDigit() || it == '.' } ->
                error("Для extended ещё не реализован перенос IP/geo-правила: ${value.take(80)}")
            else -> "domain_keyword" to value // Xray bare domain is a substring, not a suffix.
        }
        return obj { add(field, strings(text)) }
    }

    private fun obj(block: JsonObject.() -> Unit) = JsonObject().apply(block)
    private fun array(vararg items: JsonObject) = JsonArray().apply { items.forEach(::add) }
    private fun strings(vararg items: String) = JsonArray().apply { items.forEach(::add) }
}
