package com.wireturn.app.data.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Inflater

/** Client-only AWG/WG import. Administrative container credentials are never used. */
object AwgUri {
    private const val LIMIT = 1024 * 1024
    private val ints = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
    private val ranges = setOf("h1", "h2", "h3", "h4", "content_padding_addition", "rekey_after_time", "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts")
    private val bools = setOf("random_trailers", "disable_cookies")
    private val packets = (1..5).map { "i$it" }.toSet()
    val amneziaFields = ints + ranges + bools + packets + "header_protection_key"
    val queryFields = amneziaFields + setOf("private_key", "public_key", "pre_shared_key", "local_address", "allowed_ips", "dns", "mtu", "persistent_keepalive_interval", "enable_amnezia", "peers")
    private fun decode(s: String) = VpnConfig.decode(s)
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun list(s: String) = s.split(',').map(String::trim).filter(String::isNotEmpty)
    private fun key(s: String): String {
        val bytes = runCatching { Base64.getDecoder().decode(s) }.getOrElse { Base64.getUrlDecoder().decode(s) }
        require(bytes.size == 32) { "WG: ключ должен содержать 32 байта" }
        return Base64.getEncoder().encodeToString(bytes)
    }
    private fun bool(s: String) = when (s.lowercase()) { "1", "true", "on", "yes" -> true; "0", "false", "off", "no" -> false; else -> error("AWG: неверное логическое значение") }
    private fun range(s: String, maximum: Long = 4294967295): String {
        require(s.matches(Regex("[0-9]+(-[0-9]+)?"))) { "AWG: неверный диапазон" }
        val p = s.split('-').map(String::toLong)
        require(p.all { it in 0..maximum } && p.first() <= p.last()) { "AWG: диапазон вне границ" }
        return if (p.first() == p.last()) p.first().toString() else "${p.first()}-${p.last()}"
    }
    private fun ip(s: String): String {
        require(s.matches(Regex("[0-9a-fA-F:.]+"))) { "WG: ожидается IP-адрес" }
        if (':' !in s) require(s.split('.').let { it.size == 4 && it.all { octet -> octet.toIntOrNull() in 0..255 } }) { "WG: неверный IPv4" }
        val address = java.net.InetAddress.getByName(s)
        return address.hostAddress!!.substringBefore('%')
    }
    private fun cidr(s: String): String {
        val p = s.split('/'); require(p.size == 2)
        val address = ip(p[0]); val bits = p[1].toInt()
        require(bits in 0..if (':' in address) 128 else 32)
        return "$address/$bits"
    }
    private fun host(s: String): String {
        val value = s.removeSurrounding("[", "]").lowercase()
        require(value.isNotEmpty() && value.none { it.isWhitespace() || it in "/@?#\\" })
        return if (':' in value) ip(value) else value
    }
    private fun endpoint(s: String): Pair<String, Int> = URI("wg://$s").let {
        require(it.userInfo == null && it.rawQuery == null && it.rawFragment == null && it.path.isNullOrEmpty())
        require(it.port in 1..65535); host(requireNotNull(it.host)) to it.port
    }
    private fun array(values: List<String>) = JsonArray().apply { values.forEach(::add) }
    data class Parsed(val endpoint: JsonObject, val dns: List<String>, val name: String) {
        val awg: Boolean get() = endpoint.has("amnezia")
        fun uri(): String {
            val first = endpoint.getAsJsonArray("peers")[0].asJsonObject
            val q = sortedMapOf<String, String>()
            q["private_key"] = endpoint["private_key"].asString
            q["local_address"] = endpoint.getAsJsonArray("address").joinToString(",") { it.asString }
            q["mtu"] = endpoint["mtu"].asString
            q["enable_amnezia"] = awg.toString()
            if (dns.isNotEmpty()) q["dns"] = dns.joinToString(",")
            q["public_key"] = first["public_key"].asString
            first["pre_shared_key"]?.let { q["pre_shared_key"] = it.asString }
            q["allowed_ips"] = first.getAsJsonArray("allowed_ips").joinToString(",") { it.asString }
            q["persistent_keepalive_interval"] = first["persistent_keepalive_interval"].asString
            if (endpoint.getAsJsonArray("peers").size() > 1) q["peers"] = endpoint.getAsJsonArray("peers").toString()
            endpoint.getAsJsonObject("amnezia")?.entrySet()?.forEach { (k,v) -> q[k] = v.asString }
            val server = first["address"].asString.let { if (':' in it) "[$it]" else it }
            return "wg://$server:${first["port"].asInt}?" + q.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) } +
                (if (name.isBlank()) "" else "#${encode(name)}")
        }
    }
    /** Recognize a complete INI document before URI or JSON parsing can split its fields. */
    fun isIni(s: String): Boolean = s.trimStart().removePrefix("\uFEFF").lineSequence()
        .map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith(';') } == "[Interface]"
    fun recognizes(s: String) = isIni(s) || s.substringBefore(':').lowercase() in setOf("wg", "awg", "wireguard", "vpn")
    fun isAmnezia(s: String): Boolean = s.startsWith("awg://", true) || runCatching { query(URI(s))["enable_amnezia"]?.let(::bool) == true }.getOrDefault(false)
    private fun query(u: URI): Map<String, String> = linkedMapOf<String, String>().apply {
        u.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach {
            require('=' in it); val original = decode(it.substringBefore('=')).lowercase()
            val k = aliases[original] ?: original
            require(k in queryFields && put(k, decode(it.substringAfter('='))) == null) { "WG: неизвестный или повторный параметр" }
        }
    }
    fun parse(s: String): Parsed {
        require(s.length <= LIMIT)
        val u = URI(s); require(u.scheme.lowercase() in setOf("wg", "awg", "wireguard"))
        require(u.port in 1..65535 && u.path in listOf("", "/"))
        val q = query(u).toMutableMap()
        u.rawUserInfo?.let {
            val privateKey = key(decode(it))
            require(q["private_key"] == null || key(q.getValue("private_key")) == privateKey) { "WG: конфликт закрытых ключей" }
            q["private_key"] = privateKey
        }
        val peer = peer(q, host(requireNotNull(u.host)), u.port)
        val peers = q["peers"]?.let { value ->
            val a = JsonParser.parseString(value).asJsonArray; require(a.size() in 1..32)
            JsonArray().apply { a.forEach { add(validatePeer(it.asJsonObject)) } }.also { require(it[0] == peer) { "WG: conflicting peer parameters" } }
        } ?: JsonArray().apply { add(peer) }
        val out = JsonObject().apply {
            addProperty("type", "wireguard"); addProperty("tag", "vpn"); addProperty("system", false)
            addProperty("private_key", key(requireNotNull(q["private_key"])))
            val addresses = list(requireNotNull(q["local_address"])).map(::cidr).distinct().sorted(); require(addresses.isNotEmpty())
            add("address", array(addresses)); addProperty("mtu", (q["mtu"]?.toInt() ?: 1280).also { require(it in 576..65535) })
            add("peers", peers)
        }
        val hasAwg = q.keys.any { it in amneziaFields }
        val enabled = q["enable_amnezia"]?.let(::bool) ?: (hasAwg || u.scheme.equals("awg", true))
        require(enabled || !hasAwg) { "AWG: обфускация выключена при заданных параметрах" }
        if (enabled) out.add("amnezia", JsonObject().apply {
            for (k in ints) addProperty(k, (q[k]?.toInt() ?: 0).also { require(it in 0..65535) })
            require(get("jmax").asInt >= get("jmin").asInt)
            for (k in ranges) q[k]?.let { addProperty(k, range(it)) }
            for (k in packets) q[k]?.let { require(it.length <= 4096 && '\n' !in it && '\r' !in it); if (it.isNotEmpty()) addProperty(k, it) }
            for (k in bools) addProperty(k, q[k]?.let(::bool) ?: false)
            q["header_protection_key"]?.let {
                addProperty("header_protection_key", key(it))
                require((1..4).all { n -> get("s$n").asInt >= 12 }) { "AWG: защита заголовка требует S1–S4 не меньше 12" }
            }
        })
        return Parsed(out, q["dns"]?.let(::list)?.map(::ip)?.distinct().orEmpty(), u.rawFragment?.let(::decode).orEmpty())
    }
    private fun peer(q: Map<String,String>, address: String, port: Int) = JsonObject().apply {
        addProperty("address", address); addProperty("port", port); addProperty("public_key", key(requireNotNull(q["public_key"])))
        q["pre_shared_key"]?.takeIf(String::isNotEmpty)?.let { addProperty("pre_shared_key", key(it)) }
        add("allowed_ips", array(list(q["allowed_ips"] ?: "0.0.0.0/0,::/0").map(::cidr).distinct().sorted().also { require(it.isNotEmpty()) }))
        val keepalive = range(q["persistent_keepalive_interval"] ?: "0", 65535)
        // The pinned AWG engine samples ranges itself; keep scalar JSON compatible with WG.
        if ('-' in keepalive) addProperty("persistent_keepalive_interval", keepalive)
        else addProperty("persistent_keepalive_interval", keepalive.toInt())
    }
    private fun validatePeer(o: JsonObject): JsonObject {
        require(o.keySet().all { it in setOf("address", "port", "public_key", "pre_shared_key", "allowed_ips", "persistent_keepalive_interval") })
        val q = o.entrySet().filter { it.key in setOf("public_key", "pre_shared_key", "persistent_keepalive_interval") }.associate { it.key to it.value.asString }.toMutableMap()
        q["allowed_ips"] = o.getAsJsonArray("allowed_ips").joinToString(",") { it.asString }
        val port = o["port"].asInt; require(port in 1..65535)
        return peer(q, host(o["address"].asString), port)
    }
    /** The editor exposes both the first peer's scalar fields and the complete peer list. */
    internal fun reconcileEditedPeers(value: String, peerListChanged: Boolean): String {
        val u = URI(value)
        val q = query(u).toMutableMap()
        val rawPeers = q.remove("peers") ?: return value
        val source = JsonParser.parseString(rawPeers).asJsonArray
        require(source.size() in 1..32)
        val peers = JsonArray().apply { source.forEach { add(validatePeer(it.asJsonObject)) } }
        var address = requireNotNull(u.host).removeSurrounding("[", "]")
        var port = u.port
        if (peerListChanged) {
            val first = peers[0].asJsonObject
            address = first["address"].asString; port = first["port"].asInt
            q["public_key"] = first["public_key"].asString
            q.remove("pre_shared_key")
            first["pre_shared_key"]?.let { q["pre_shared_key"] = it.asString }
            q["allowed_ips"] = first.getAsJsonArray("allowed_ips").joinToString(",") { it.asString }
            q["persistent_keepalive_interval"] = first["persistent_keepalive_interval"].asString
        }
        val base = parse("wg://${if (':' in address) "[$address]" else address}:$port?" +
            q.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) } +
            (u.rawFragment?.let { "#$it" } ?: ""))
        if (!peerListChanged) peers.set(0, base.endpoint.getAsJsonArray("peers")[0])
        base.endpoint.add("peers", peers)
        return base.uri()
    }
    private fun unwrap(s: String): String {
        require(s.length <= LIMIT * 2)
        val bytes = Base64.getUrlDecoder().decode(s.replace('+','-').replace('/','_'))
        require(bytes.size <= LIMIT)
        fun text(b: ByteArray) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString()
        if (bytes.firstOrNull()?.toInt() in listOf('['.code, '{'.code, '#'.code, ';'.code, ' '.code, '\n'.code)) return text(bytes)
        require(bytes.size >= 6)
        val size = ByteBuffer.wrap(bytes,0,4).int; require(size in 1..LIMIT)
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes,4,bytes.size-4)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf); require(n > 0 || inflater.finished()); require(out.size()+n <= size)
                out.write(buf,0,n)
            }
            require(out.size() == size && inflater.remaining == 0)
            text(out.toByteArray())
        } finally { inflater.end() }
    }
    fun normalizeAll(source: String): List<String> {
        if (isIni(source)) return listOf(ini(source.trimStart().removePrefix("\uFEFF"), "").uri())
        if (!source.startsWith("vpn://",true)) return listOf(parse(source).uri())
        val body = source.substringAfter("://"); val name = body.substringAfter('#', "").let(::decode)
        val decoded = unwrap(body.substringBefore('#')).trim()
        if (decoded.startsWith("{")) return containers(JsonParser.parseString(decoded).asJsonObject, name)
        return listOf(ini(decoded,name).uri())
    }
    private fun containers(o: JsonObject, suppliedName: String, depth: Int = 0): List<String> {
        require(depth < 8)
        val name = suppliedName.ifBlank { o["description"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty() }
        o["config"]?.takeIf { it.isJsonPrimitive && it.asString.contains("[Interface]") }?.let { return listOf(ini(it.asString,name).uri()) }
        o["last_config"]?.let { return containers(if (it.isJsonPrimitive) JsonParser.parseString(it.asString).asJsonObject else it.asJsonObject, name, depth+1) }
        o["awg"]?.takeIf { it.isJsonObject }?.let { return containers(it.asJsonObject,name,depth+1) }
        val all = o.getAsJsonArray("containers") ?: error("Amnezia: контейнер не содержит клиентской конфигурации AWG")
        require(all.size() in 1..32)
        val result = all.filter { it.isJsonObject && (it.asJsonObject.has("awg") || it.asJsonObject["container"]?.asString in setOf("amnezia-awg", "amnezia-awg2")) }
            .flatMap { containers(it.asJsonObject,name,depth+1) }
        require(result.isNotEmpty()); return result.distinct()
    }
    private val aliases = mapOf("privatekey" to "private_key", "publickey" to "public_key", "presharedkey" to "pre_shared_key", "address" to "local_address", "allowedips" to "allowed_ips", "persistentkeepalive" to "persistent_keepalive_interval", "keepalive" to "persistent_keepalive_interval", "headerprotectionkey" to "header_protection_key", "contentpaddingaddition" to "content_padding_addition", "rekeyaftertime" to "rekey_after_time", "rekeytimeout" to "rekey_timeout", "rejectaftertime" to "reject_after_time", "keepalivetimeout" to "keepalive_timeout", "maxhandshakeattempts" to "max_handshake_attempts", "randomtrailers" to "random_trailers", "disablecookies" to "disable_cookies")
    // Messaging clients escape angle brackets in the documented byte/random packet tags.
    // Decode only those tags in I1-I5; credentials and other backslashes stay literal.
    private val presentedPacketTag = Regex("""\\?<([br]\s+[^<>\\\r\n]+)\\?>""")
    private fun packetPresentation(value: String) = presentedPacketTag.replace(value) { "<${it.groupValues[1]}>" }
    private fun ini(text: String, suppliedName: String): Parsed {
        require(text.length <= LIMIT)
        val global = linkedMapOf<String,String>(); val peers = mutableListOf<MutableMap<String,String>>()
        var section = ""; var name = suppliedName; var hasInterface = false
        var pendingPeerName: String? = null
        for (raw in text.lines()) {
            val line = raw.trim(); if (line.isEmpty()) continue
            val markdownName = line.takeIf { it.startsWith("**") && it.endsWith("**") && it.length > 4 }
                ?.removeSurrounding("**")?.trim()?.takeIf { it.length in 1..120 && '=' !in it && "://" !in it }
            if (line.startsWith('#') || line.startsWith(';') || markdownName != null) {
                require(markdownName == null || (section == "[Interface]" && peers.isEmpty())) { "WG: неверное расположение имени INI" }
                val comment = markdownName ?: line.drop(1).trim()
                if (name.isBlank() && section.isEmpty() && comment.length in 1..120 && !comment.contains('=') && !comment.contains("://"))
                    name = comment.removePrefix("Name:").removePrefix("name:").trim()
                // Panel INI exports label the first peer immediately above [Peer].
                // A comment beside an ordinary setting must not become the profile name.
                if (section == "[Interface]" && peers.isEmpty()) pendingPeerName = comment
                    .takeIf { it.length in 1..120 && !it.contains('=') && !it.contains("://") }
                    ?.removePrefix("Name:")?.removePrefix("name:")?.trim()
                continue
            }
            if (line.startsWith('[')) {
                require(line in listOf("[Interface]", "[Peer]"))
                if (line == "[Interface]") { require(!hasInterface && peers.isEmpty()); hasInterface = true }
                else require(hasInterface)
                section = line
                if (section == "[Peer]" && peers.isEmpty() && name.isBlank()) name = pendingPeerName.orEmpty()
                pendingPeerName = null
                if (section == "[Peer]") { require(peers.size < 32); peers += linkedMapOf<String,String>() }
                continue
            }
            pendingPeerName = null
            require('=' in line && section.isNotEmpty())
            val originalKey = line.substringBefore('=').trim().lowercase(); val k = aliases[originalKey] ?: originalKey
            require(k in queryFields || k == "endpoint") { "WG: неподдерживаемое поле INI" }
            val target = if (section == "[Interface]") global else peers.last()
            val value = line.substringAfter('=').trim().let { if (k in packets) packetPresentation(it) else it }
            require(target.put(k,value) == null) { "WG: повторное поле INI" }
        }
        require(peers.isNotEmpty())
        val peerList = peers.map { p ->
            require(p.keys.all { it in setOf("endpoint", "public_key", "pre_shared_key", "allowed_ips", "persistent_keepalive_interval") })
            val (h,port) = endpoint(requireNotNull(p["endpoint"])); peer(p,h,port)
        }
        require(global.keys.none { it in setOf("peers", "endpoint", "public_key", "pre_shared_key", "allowed_ips", "persistent_keepalive_interval") })
        val first = peers.first(); val (h,port) = endpoint(requireNotNull(first["endpoint"]))
        val q = global + first.filterKeys { it != "endpoint" } + if (peerList.size > 1) mapOf("peers" to JsonArray().apply { peerList.forEach(::add) }.toString()) else emptyMap()
        return parse("wg://${if (':' in h) "[$h]" else h}:$port?" + q.entries.joinToString("&") { encode(it.key)+"="+encode(it.value) } + if(name.isBlank()) "" else "#${encode(name)}")
    }
    /** Same effective connection, independent of source envelope and presentation name. */
    /** Same server/account across representations; tuning stays in the selected source variant. */
    fun accountIdentity(uri: String): String {
        val endpoint = parse(uri).endpoint.deepCopy()
        endpoint.remove("mtu")
        endpoint.getAsJsonArray("peers").forEach { peer ->
            peer.asJsonObject.remove("allowed_ips")
            peer.asJsonObject.remove("persistent_keepalive_interval")
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(endpoint.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun identity(uri: String): String {
        val p = parse(uri)
        val value = p.endpoint.toString() + "|dns=" + p.dns.joinToString(",")
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
