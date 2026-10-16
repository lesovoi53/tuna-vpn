package com.wireturn.app.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.RouteAction
import com.wireturn.app.data.RoutingMode
import com.wireturn.app.data.RoutingPolicy
import com.wireturn.app.data.RoutingRule
import java.net.URI
import java.util.Base64

/** Untrusted subscription metadata -> preview model. Does not mutate settings or start a tunnel. */
object SubscriptionPolicyParser {
    private const val MAX_JSON_BYTES = 256 * 1024
    private const val PREFIX = "happ://routing/onadd/"
    fun activates(link: String): Boolean = link.startsWith(PREFIX)

    data class Resolver(val transport: String, val endpoint: String, val bootstrapIp: String?, val route: RouteAction)
    data class HappPolicy(
        val name: String,
        val enabled: Boolean,
        val routing: RoutingPolicy,
        val remote: Resolver,
        val domestic: Resolver,
        val hosts: Map<String, String>,
        val domainStrategy: String,
        val fakeDns: Boolean,
        val geoipUrl: String?,
        val geositeUrl: String?,
        val source: JsonObject,
        val warnings: List<String>,
        val dns: SplitDnsSettings? = null,
    )

    fun fromHeaders(headers: Map<String, List<String>>): HappPolicy? {
        fun header(name: String): String? {
            val values = headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
            require(values.size <= 1) { "Duplicate $name header" }
            return values.singleOrNull()
        }
        val source = header("Routing") ?: return null
        return parseHapp(source, header("Routing-Enable")?.let(::booleanText) ?: false)
    }

    fun parseHapp(link: String, enabled: Boolean = false): HappPolicy {
        val prefix = if (link.startsWith("happ://routing/add/")) "happ://routing/add/" else PREFIX
        require(link.startsWith(prefix) && link.length <= 350000) { "Invalid Happ routing URI" }
        val encoded = link.removePrefix(prefix)
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { Base64.getUrlDecoder().decode(encoded) }
        require(bytes.size <= MAX_JSON_BYTES) { "Routing document too large" }
        val json = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JsonParser.parseString(json).asJsonObject
        fun string(key: String, default: String? = null): String = root.get(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Expected string: $key" }
            it.asString
        } ?: default ?: error("Missing $key")
        fun bool(key: String, default: Boolean = false): Boolean = root.get(key)?.let {
            require(it.isJsonPrimitive && (it.asJsonPrimitive.isBoolean || it.asJsonPrimitive.isString)) { "Expected boolean: $key" }
            booleanText(it.asString)
        } ?: default
        val order = string("RouteOrder", "block-proxy-direct").split('-')
        require(order.size == 3 && order.toSet() == setOf("block", "proxy", "direct")) { "Invalid RouteOrder" }
        val rules = order.flatMap { action ->
            val (stem, target) = when (action) {
                "block" -> "Block" to RouteAction.BLOCK
                "proxy" -> "Proxy" to RouteAction.VPN
                else -> "Direct" to RouteAction.DIRECT
            }
            listOf("Sites", "Ip").flatMap { suffix ->
                root.get(stem + suffix)?.let { array ->
                    require(array.isJsonArray) { "Expected array: $stem$suffix" }
                    array.asJsonArray.map { value ->
                        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected routing match" }
                        val match = value.asString
                        require(RoutingPolicy.validMatch(match)) { "Invalid routing match" }
                        // Domain and address lists must retain their distinct semantics.
                        val address = match.removePrefix("geoip:")
                        if (suffix == "Ip") require(match.startsWith("geoip:") || isIp(address.substringBefore('/'))) { "Expected IP rule" }
                        else require(!match.startsWith("geoip:") && !isIp(match.substringBefore('/'))) { "Expected domain rule" }
                        RoutingRule(match, target)
                    }
                }.orEmpty()
            }
        }
        val routing = RoutingPolicy(if (bool("GlobalProxy", true)) RoutingMode.SELECTED_DIRECT else RoutingMode.SELECTED_VPN, rules)
        routing.validate()
        fun resolver(prefix: String, route: RouteAction): Resolver {
            val legacy = root.get(prefix + "Dns")?.asString
            val type = string(prefix + "DNSType", "UDP").uppercase()
            require(type in setOf("DOH", "UDP", "TCP")) { "Unsupported DNS transport" }
            val endpoint = if (type == "DOH") string(prefix + "DNSDomain") else string(prefix + "Dns", legacy)
            if (type == "DOH") httpsUrl(endpoint) else {
                val u = URI("$type://$endpoint")
                require(!u.host.isNullOrBlank() && u.userInfo == null && u.query == null && u.fragment == null && u.path.isNullOrEmpty()) { "Invalid DNS endpoint" }
                require(u.port == -1 || u.port in 1..65535)
            }
            val ip = root.get(prefix + "DNSIP")?.asString?.takeIf { it.isNotBlank() }
            require(ip == null || isIp(ip)) { "Invalid bootstrap IP" }
            return Resolver(type, endpoint, ip, route)
        }
        val hosts = root.get("DnsHosts")?.let { value ->
            require(value.isJsonObject) { "Expected DnsHosts object" }
            value.asJsonObject.entrySet().associate { (name, address) ->
                require(RoutingPolicy.validMatch(name) && !name.contains(':') && !isIp(name)) { "Invalid static host" }
                require(address.isJsonPrimitive && address.asJsonPrimitive.isString && isIp(address.asString)) { "Invalid static IP" }
                name to address.asString
            }
        }.orEmpty()
        val strategy = string("DomainStrategy", "AsIs")
        require(strategy in setOf("AsIs", "IPIfNonMatch", "IPOnDemand")) { "Unsupported DomainStrategy" }
        val known = setOf("Name", "GlobalProxy", "RouteOrder", "DirectSites", "DirectIp", "ProxySites", "ProxyIp", "BlockSites", "BlockIp", "DomainStrategy", "FakeDNS", "DnsHosts", "Geoipurl", "Geositeurl", "UseChunkFiles", "LastUpdated", "RemoteDns", "DomesticDns", "RemoteDNSType", "DomesticDNSType", "RemoteDNSDomain", "DomesticDNSDomain", "RemoteDNSIP", "DomesticDNSIP")
        val warnings = mutableListOf<String>()
        if (bool("UseChunkFiles")) warnings += "Chunk files are not applied; DAT assets require validation before activation"
        root.keySet().filter { it !in known && it != "TunaDNS" }.forEach { warnings += "Unknown Happ field: $it" }
        fun asset(key: String) = root.get(key)?.let { string(key).also(::httpsUrl) }
        val remote = resolver("Remote", RouteAction.VPN)
        val domestic = resolver("Domestic", RouteAction.DIRECT)
        val dns = root["TunaDNS"]?.let { SplitDnsSettings.parse(it.toString(), SplitDnsSettings(remote, domestic, hosts)) }
        return HappPolicy(string("Name", "Happ routing"), enabled, routing,
            dns?.remote ?: remote, dns?.domestic ?: domestic, hosts,
            strategy, bool("FakeDNS"), asset("Geoipurl"), asset("Geositeurl"), root.deepCopy(), warnings, dns)
    }

    private fun booleanText(value: String): Boolean = when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> error("Invalid boolean")
    }

    private fun httpsUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) { "Expected HTTPS URL" }
    }

    private fun isIp(value: String): Boolean {
        if (value.contains(':')) return value.matches(Regex("[0-9a-fA-F:]+")) &&
            runCatching { java.net.InetAddress.getByName(value) is java.net.Inet6Address }.getOrDefault(false)
        return value.split('.').let { it.size == 4 && it.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255 } }
    }
}
