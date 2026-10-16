package com.wireturn.app.domain

import com.wireturn.app.data.*
import java.io.File
import java.net.InetAddress

/** Static explanation of a candidate policy. Never resolves a hostname using the system DNS. */
object RouteInspector {
    data class Result(val action: RouteAction?, val match: String?, val dnsAction: RouteAction?, val detail: String)
    fun inspect(policy: RoutingPolicy, input: String, ip: String = "", geoDir: File? = null,
                domainStrategy: String = "IPIfNonMatch"): Result {
        val host = input.trim().trimEnd('.').lowercase().let { if (StaticHosts.isIp(it)) it else java.net.IDN.toASCII(it) }
        require(host.isNotBlank() && (StaticHosts.isIp(host) || RoutingPolicy.validMatch("full:$host"))) { "Введите домен или IP без URL и пути" }
        require(ip.isBlank() || StaticHosts.isIp(ip)) { "Некорректный IP" }
        val address = if (StaticHosts.isIp(host)) host else ip
        val rules = policy.activeRules()
        val fallback = if (policy.mode == RoutingMode.SELECTED_VPN) RouteAction.DIRECT else RouteAction.VPN
        val geo = GeoMatcher(geoDir)
        var domainRule: RoutingRule? = null
        var unknown: String? = null
        if (!StaticHosts.isIp(host)) for (rule in rules.filterNot { isIpRule(it.match) }) {
            val hit = if (rule.match.startsWith("geosite:")) geo.matches(rule.match, host) else matchesDomain(rule.match, host)
            if (hit == null) { unknown = rule.match; break }
            if (hit) { domainRule = rule; break }
        }
        if (unknown != null) return Result(null, unknown, null, "Нельзя точно проверить категорию: нет базы, категории или поддерживаемого условия. Прямой сетевой запрос не выполнялся.")
        val dns = domainRule?.action ?: fallback
        if (domainRule != null && domainStrategy != "IPOnDemand") return Result(domainRule.action, domainRule.match, dns, "Первое совпавшее доменное правило")
        if (address.isBlank()) {
            if (domainStrategy != "AsIs" && rules.any { isIpRule(it.match) }) return Result(null, null, dns, "Для окончательного маршрута нужен IP домена. Укажите его ниже; системный DNS не используется.")
            return Result(domainRule?.action ?: fallback, domainRule?.match, dns, if (domainRule == null) "Действие по умолчанию" else "Доменное правило")
        }
        for (rule in rules) {
            val hit = when {
                isIpRule(rule.match) -> if (rule.match.startsWith("geoip:")) geo.matches(rule.match, address) else cidrContains(rule.match, address)
                StaticHosts.isIp(host) -> false
                rule.match.startsWith("geosite:") -> geo.matches(rule.match, host)
                else -> matchesDomain(rule.match, host)
            }
            if (hit == null) return Result(null, rule.match, dns, "Категорию нельзя проверить по доступной базе")
            if (hit) return Result(rule.action, rule.match, dns, "Первое совпавшее правило по указанному домену/IP")
        }
        return Result(fallback, null, dns, "Совпадений нет: действие по умолчанию")
    }
    private fun isIpRule(s: String) = s.startsWith("geoip:") || StaticHosts.isIp(s.substringBefore('/'))
    internal fun matchesDomain(rule: String, host: String): Boolean? = when {
        rule.startsWith("full:") -> host.equals(rule.substringAfter(':'), true)
        rule.startsWith("keyword:") -> host.contains(rule.substringAfter(':'), true)
        rule.startsWith("regexp:") -> RoutingRegex.matches(rule.substringAfter(':'), host)
        else -> rule.removePrefix("domain:").let { host.equals(it, true) || host.endsWith(".$it", true) }
    }
    internal fun cidrContains(cidr: String, ip: String): Boolean {
        if (!StaticHosts.isIp(cidr.substringBefore('/')) || !StaticHosts.isIp(ip)) return false
        val network = InetAddress.getByName(cidr.substringBefore('/')).address
        val candidate = InetAddress.getByName(ip).address
        if (network.size != candidate.size) return false
        val bits = cidr.substringAfter('/', "${network.size * 8}").toIntOrNull() ?: return false
        if (bits !in 0..network.size * 8) return false
        for (i in 0 until bits) {
            val mask = 1 shl (7 - i % 8)
            if ((network[i / 8].toInt() and mask) != (candidate[i / 8].toInt() and mask)) return false
        }
        return true
    }
    private class GeoMatcher(val directory: File?) {
        private val files = mutableMapOf<String, ByteArray>()
        fun matches(spec: String, target: String): Boolean? = runCatching {
            val ip = spec.startsWith("geoip:")
            val key = spec.substringAfter(':').lowercase()
            require('!' !in key) // Do not guess inverted attributes.
            val category = key.substringBefore('@')
            val requiredAttributes = key.split('@').drop(1)
            require(!ip || requiredAttributes.isEmpty())
            val filename = if (ip) "geoip.dat" else "geosite.dat"
            val bytes = files.getOrPut(filename) {
                val file = File(requireNotNull(directory), filename)
                require(file.isFile && file.length() <= 80_000_000)
                file.readBytes()
            }
            val entry = Proto(bytes).fields().filter { it.tag == 1 && it.bytes != null }.firstOrNull { field ->
                Proto(field.bytes!!).fields().any { it.tag == 1 && it.text().equals(category, true) }
            } ?: return null
            val fields = Proto(entry.bytes!!).fields().toList()
            var uncertain = false
            val matched = fields.filter { it.tag == 2 && it.bytes != null }.any { field ->
                val item = Proto(field.bytes!!).fields().toList()
                if (ip) {
                    val address = item.first { it.tag == 1 }.bytes!!
                    val prefix = item.firstOrNull { it.tag == 2 }?.number ?: 0
                    cidrContains("${InetAddress.getByAddress(address).hostAddress}/$prefix", target)
                } else {
                    val attributes = item.filter { it.tag == 3 && it.bytes != null }.mapNotNull { attribute ->
                        Proto(attribute.bytes!!).fields().firstOrNull { it.tag == 1 }?.text()
                    }
                    if (!attributes.containsAll(requiredAttributes)) false else {
                        val type = item.firstOrNull { it.tag == 1 }?.number ?: 0
                        val value = item.first { it.tag == 2 }.text()
                        val result = when (type) {
                            0 -> target.contains(value)
                            1 -> matchesDomain("regexp:$value", target)
                            2 -> matchesDomain("domain:$value", target)
                            3 -> matchesDomain("full:$value", target)
                            else -> null
                        }
                        if (result == null) uncertain = true
                        result == true
                    }
                }
            }
            if (!matched && uncertain) null else matched.xor(ip && fields.any { it.tag == 3 && it.number != 0 })
        }.getOrNull()
    }
    private data class Field(val tag: Int, val number: Int = 0, val bytes: ByteArray? = null) { fun text() = bytes?.toString(Charsets.UTF_8).orEmpty() }
    private class Proto(val bytes: ByteArray) {
        var at = 0
        fun number(): Int { var value = 0; var shift = 0
            while (at < bytes.size && shift < 35) { val b = bytes[at++].toInt() and 255; value = value or ((b and 127) shl shift); if (b and 128 == 0) return value; shift += 7 }
            error("Invalid protobuf")
        }
        fun fields(): Sequence<Field> = sequence {
            while (at < bytes.size) { val key = number(); when (key and 7) {
                0 -> yield(Field(key ushr 3, number()))
                2 -> { val length = number(); require(length >= 0 && length <= bytes.size - at); val field = Field(key ushr 3, bytes = bytes.copyOfRange(at, at + length)); at += length; yield(field) }
                1 -> at += 8
                5 -> at += 4
                else -> error("Invalid protobuf wire type")
            }; require(at <= bytes.size) }
        }
    }
}
