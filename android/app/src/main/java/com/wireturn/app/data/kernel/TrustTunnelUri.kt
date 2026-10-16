package com.wireturn.app.data.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64

/** Normalizes panel links and official TLV links without discarding connection options. */
object TrustTunnelUri {
    private fun encode(s: String) = URLEncoder.encode(s,"UTF-8").replace("+", "%20")
    /** QUIC varints have 1/2/4/8-byte width, including lengths and numeric field values. */
    private class Reader(val bytes: ByteArray) {
        var position = 0
        fun number(): Long {
            require(position < bytes.size)
            val first = bytes[position++].toInt() and 255
            val size = 1 shl (first ushr 6); require(size - 1 <= bytes.size - position)
            var value = (first and 63).toLong()
            repeat(size-1) { value = (value shl 8) or (bytes[position++].toLong() and 255) }
            return value
        }
        fun chunk(): ByteArray {
            val size = number(); require(size <= bytes.size-position)
            return bytes.copyOfRange(position,position+size.toInt()).also { position += size.toInt() }
        }
    }
    fun normalize(value: String): String {
        if (!value.startsWith("tt://?",true)) return value
        require(value.length <= 131072)
        val r = Reader(Base64.getUrlDecoder().decode(value.substring(6)))
        val fields = mutableMapOf<Long, ByteArray>(); val addresses = mutableListOf<String>()
        fun text(b: ByteArray) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString()
        while (r.position < r.bytes.size) {
            val tag = r.number(); val b = r.chunk()
            if (tag == 2L) addresses += text(b) else fields[tag] = b
        }
        fun field(n: Long) = fields[n]?.let(::text)
        fun number(n: Long, default: Long): Long = fields[n]?.let { b -> Reader(b).let { q -> q.number().also { require(q.position == b.size) } } } ?: default
        fun boolean(n: Long, default: Boolean): Boolean = fields[n]?.let { require(it.size == 1 && it[0].toInt() in 0..1); it[0].toInt() == 1 } ?: default
        require(number(0,0) in 0..2) { "TrustTunnel: неподдерживаемая версия ссылки" }
        require(fields[14] == null) { "TrustTunnel: ссылка на удалённую подписку требует отдельного импорта" }
        require(addresses.size == 1) { "TrustTunnel: для этого профиля требуется один адрес сервера" }
        val address = URI("tt://${addresses.single()}")
        require(!address.host.isNullOrBlank() && address.port in 1..65535 && address.rawUserInfo == null && address.rawQuery == null && address.rawFragment == null && address.path.isNullOrEmpty())
        val hostname = requireNotNull(field(1)); val user = requireNotNull(field(5)); val password = requireNotNull(field(6))
        require(hostname.isNotBlank() && user.isNotEmpty() && password.isNotEmpty())
        val q = linkedMapOf("security" to "tls", "sni" to (field(3) ?: hostname))
        when(number(9,1)) { 1L -> q["quic"] = "false"; 2L -> q["quic"] = "true"; else -> error("TrustTunnel: неизвестный транспорт") }
        if (boolean(7,false)) q["insecure"] = "true"
        if (!boolean(4,true)) q["has_ipv6"] = "false"
        if (boolean(10,false)) q["anti_dpi"] = "true"
        field(11)?.let { q["client_random_prefix"] = it }
        fields[8]?.let { der ->
            val certificates = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(der.inputStream())
            require(certificates.isNotEmpty())
            q["certificate"] = certificates.joinToString("\n") { "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(it.encoded) + "\n-----END CERTIFICATE-----" }
        }
        fields[13]?.let { bytes -> val list = mutableListOf<String>(); val d = Reader(bytes)
            while(d.position < bytes.size) list += text(d.chunk())
            q["dns"] = list.joinToString(",")
        }
        return "tt://${encode(user)}:${encode(password)}@${address.rawAuthority}?" + q.entries.joinToString("&") { encode(it.key)+"="+encode(it.value) } +
            (field(12)?.let { "#${encode(it)}" } ?: "")
    }
    fun outbound(value: String): JsonObject {
        val u = URI(value)
        require(u.scheme.lowercase() in setOf("tt", "trusttunnel") && !u.host.isNullOrBlank() && u.port in 1..65535)
        require(u.path in listOf("", "/"))
        val auth = requireNotNull(u.rawUserInfo).split(':',limit=2)
        require(auth.size == 2)
        val q = linkedMapOf<String,String>()
        u.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach {
            require('=' in it)
            require(q.put(VpnConfig.decode(it.substringBefore('=')), VpnConfig.decode(it.substringAfter('='))) == null)
        }
        val allowed = setOf("security", "sni", "alpn", "insecure", "allowInsecure", "allow_insecure", "fp", "network", "health_check", "quic", "congestion_control", "congestion_controller", "cwnd", "certificate", "certificate_public_key_sha256", "client_random_prefix", "has_ipv6", "anti_dpi", "dns", "use_cronet_https", "use_cronet_quic", "force_quic", "quic_congestion_control")
        require(q.keys.all { it in allowed }) { "TrustTunnel: неподдерживаемый параметр" }
        require(q["security"] in listOf(null,"tls")) { "TrustTunnel требует TLS" }
        fun bool(s: String) = when (s) { "true","1" -> true; "false","0" -> false; else -> error("TrustTunnel: неверное логическое значение") }
        val quic = q["quic"]?.let(::bool) == true
        val cronet = q[if (quic) "use_cronet_quic" else "use_cronet_https"]?.let(::bool) == true
        require(q["force_quic"]?.let(::bool) != true || (quic && cronet)) { "Force QUIC требует QUIC и стек Chrome" }
        if (cronet) require(q["fp"].isNullOrBlank() && q["client_random_prefix"].isNullOrBlank() &&
            q["certificate_public_key_sha256"].isNullOrBlank() && q["alpn"].isNullOrBlank() &&
            listOf("insecure", "allowInsecure", "allow_insecure").none { q[it]?.let(::bool) == true }) {
            "Стек Chrome несовместим с uTLS, префиксом random, ALPN, SPKI pin и отключением проверки TLS"
        }
        require(!quic || q["fp"].isNullOrBlank()) { "Отпечаток uTLS доступен только для HTTPS" }
        return JsonObject().apply {
            addProperty("type", "trusttunnel"); addProperty("tag", "vpn")
            addProperty("server", u.host.removeSurrounding("[", "]")); addProperty("server_port", u.port)
            addProperty("username", VpnConfig.decode(auth[0]).also { require(it.isNotEmpty()) })
            addProperty("password", VpnConfig.decode(auth[1]).also { require(it.isNotEmpty()) })
            q["network"]?.let { require(it in setOf("auto","tcp","udp")); if (it != "auto") addProperty("network",it) }
            listOf("health_check", "quic", "use_cronet_https", "use_cronet_quic", "force_quic").forEach { k -> q[k]?.let { addProperty(k,bool(it)) } }
            val controllers = listOfNotNull(q["congestion_controller"],q["congestion_control"],q["quic_congestion_control"]).distinct()
            require(controllers.size <= 1) { "TrustTunnel: конфликт контроллеров QUIC" }
            controllers.firstOrNull()?.let {
                require(it in (if (cronet) setOf("bbr", "bbr2", "cubic", "reno") else setOf("bbr", "cubic", "new_reno")))
                addProperty("congestion_controller", it)
            }
            q["cwnd"]?.let { addProperty("cwnd",it.toInt().also { n -> require(n in 1..65535) }) }
            q["has_ipv6"]?.let { require(bool(it)) { "TrustTunnel: ограничение удалённого IPv6 пока не поддерживается" } }
            q["anti_dpi"]?.let { require(!bool(it)) { "TrustTunnel: anti_dpi пока не поддерживается этим ядром" } }
            q["dns"]?.let { require(it.isBlank()) { "TrustTunnel: DNS из TLV требует настройки маршрутизации" } }
            q["client_random_prefix"]?.let {
                require(it.matches(Regex("[0-9a-fA-F]{2,64}(/[0-9a-fA-F]{2,64})?")) && it.split('/').all { v -> v.length % 2 == 0 })
                require('/' !in it || it.substringBefore('/').length == it.substringAfter('/').length)
                addProperty("client_random_prefix", it)
            }
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", (q["sni"] ?: u.host).also { require(it.isNotBlank() && it.none(Char::isWhitespace)) })
                q["alpn"]?.let { add("alpn", JsonArray().apply { it.split(',').forEach(::add) }) }
                val insecure = listOfNotNull(q["insecure"],q["allowInsecure"],q["allow_insecure"]).map(::bool).distinct()
                require(insecure.size <= 1); insecure.firstOrNull()?.let { addProperty("insecure",it) }
                q["fp"]?.let { add("utls", JsonObject().apply { addProperty("enabled", true); addProperty("fingerprint",it) }) }
                q["certificate"]?.let { add("certificate",JsonArray().apply { add(it) }) }
                q["certificate_public_key_sha256"]?.let { add("certificate_public_key_sha256", JsonArray().apply { it.split(',').forEach(::add) }) }
            })
        }
    }
}
