package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import java.net.URI
import java.net.URLEncoder

enum class CfUnavailable { PROTOCOL, TRANSPORT, HTTP3, TLS, PORT, HOST, OVERRIDE, DOWNLOAD, INVALID }
class CfProfileException(val reason: CfUnavailable) : IllegalArgumentException(reason.name)

/** The name is an explicit user opt-in, never evidence that a server uses Cloudflare. */
object CloudflareProfile {
    private val tag = Regex("(?<![\\p{L}\\p{N}\\p{M}_])cf(?![\\p{L}\\p{N}\\p{M}_])", RegexOption.IGNORE_CASE)
    fun tagged(name: String) = tag.containsMatchIn(name)
    /** Mirror pinned Xray splithttp.decideHTTPVersion: a mixed ALPN list selects HTTP/2. */
    internal fun usesHttp3(alpn: List<String>) = alpn == listOf("h3")
    fun originalAddress(profile: Profile): String = runCatching {
        URI((profile.kernelConfig as? KernelConfig.Vpn)?.config?.uri.orEmpty()).host.orEmpty().removeSurrounding("[", "]")
    }.getOrDefault("")
    fun equivalent(a: VpnConfig?, b: VpnConfig): Boolean = runCatching {
        requireNotNull(a)
        val x = URI(a.uri); val y = URI(b.uri)
        a.selectedEngine == b.selectedEngine && (a.options ?: com.wireturn.app.data.kernel.VpnOptions()) ==
            (b.options ?: com.wireturn.app.data.kernel.VpnOptions()) && x.scheme == y.scheme && x.rawAuthority == y.rawAuthority &&
            x.rawPath.orEmpty().trimEnd('/') == y.rawPath.orEmpty().trimEnd('/') && x.rawFragment == y.rawFragment && query(x) == query(y)
    }.getOrDefault(false)
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    fun snapshot(profile: Profile): String = gson.toJson(gson.toJsonTree(profile).asJsonObject.apply {
        // A local refresh preference does not change the connection or invalidate CF measurements.
        remove("keepSubscriptionAddress")
    })
    fun restore(snapshot: String): Profile = gson.fromJson(snapshot, Profile::class.java)
    fun query(uri: URI): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach {
            val key = VpnConfig.decode(it.substringBefore('='))
            require(!out.containsKey(key))
            out[key] = VpnConfig.decode(it.substringAfter('=', ""))
        }
        return out
    }
    private fun fail(reason: CfUnavailable): Nothing = throw CfProfileException(reason)
    fun plan(profile: Profile): CfProfilePlan {
        try {
            val config = (profile.kernelConfig as? KernelConfig.Vpn)?.config ?: fail(CfUnavailable.PROTOCOL)
            if (config.protocol !in setOf("vless", "trojan") || !config.usesXray) fail(CfUnavailable.PROTOCOL)
            config.validate()
            val uri = URI(config.uri)
            val q = query(uri)
            val transport = q["type"].orEmpty()
            if (transport !in setOf("ws", "grpc", "httpupgrade", "xhttp", "splithttp")) fail(CfUnavailable.TRANSPORT)
            if (transport in setOf("xhttp", "splithttp") && usesHttp3(q["alpn"].orEmpty().split(','))) fail(CfUnavailable.HTTP3)
            if ((q["security"] ?: if (config.protocol == "trojan") "tls" else "none") != "tls") fail(CfUnavailable.TLS)
            if (uri.port !in setOf(443, 2053, 2083, 2087, 2096, 8443)) fail(CfUnavailable.PORT)
            // Alternate native destinations/masks/ECH DNS would defeat the physical-network relay.
            val patch = config.options?.forEngine("xray")
            if (patch?.has("settings") == true || patch?.has("streamSettings") == true ||
                listOf("fm", "ech", "echConfigList", "echForceQuery").any { !q[it].isNullOrBlank() } ||
                !q["flow"].isNullOrEmpty()) fail(CfUnavailable.OVERRIDE)
            val extra = q["extra"]?.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it).asJsonObject }
            if (extra != null && (extra.has("address") || extra.has("port"))) fail(CfUnavailable.OVERRIDE)
            val sni = (q["sni"] ?: q["serverName"]).orEmpty().ifBlank { uri.host }
            if (sni.isNullOrBlank() || CfAddress.literal(sni) != null) fail(CfUnavailable.HOST)
            // Once the dial address is replaced, keep the original HTTP authority explicitly.
            val host = q["host"].orEmpty().ifBlank { extra?.get("host")?.asString.orEmpty() }.ifBlank { sni }
            if (host.any { it == '\r' || it == '\n' } || sni.any { it.isWhitespace() }) fail(CfUnavailable.HOST)
            val download = extra?.get("downloadSettings")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { d ->
                val address = d.get("address")?.asString?.takeIf { it.isNotBlank() } ?: fail(CfUnavailable.DOWNLOAD)
                val port = d.get("port")?.asInt ?: fail(CfUnavailable.DOWNLOAD)
                val tls = d.getAsJsonObject("tlsSettings")
                val settings = d.getAsJsonObject("xhttpSettings") ?: d.getAsJsonObject("splithttpSettings")
                // Explicit names are required; otherwise replacing the address changes implicit SNI/Host.
                if (d.get("security")?.asString != "tls" ||
                    (d.get("network") ?: d.get("method"))?.asString !in setOf("xhttp", "splithttp") ||
                    tls?.get("serverName")?.asString.isNullOrBlank() || settings?.get("host")?.asString.isNullOrBlank() ||
                    d.has("sockopt") || d.has("finalmask") || settings.has("downloadSettings") ||
                    settings.has("extra") || tls?.has("echConfigList") == true ||
                    port !in 1..65535) fail(CfUnavailable.DOWNLOAD)
                if (usesHttp3(tls?.getAsJsonArray("alpn")?.map { it.asString }.orEmpty())) fail(CfUnavailable.HTTP3)
                CfEndpoint(address, port)
            }
            val frozen = snapshot(profile)
            return CfProfilePlan(frozen, uri, q, sni, host, download)
        } catch (e: CfProfileException) { throw e }
        catch (_: Exception) { fail(CfUnavailable.INVALID) }
    }
    internal fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

data class CfEndpoint(val host: String, val port: Int)
class CfProfilePlan internal constructor(
    val snapshot: String, private val uri: URI,
    private val query: Map<String, String>, private val sni: String, private val host: String,
    val download: CfEndpoint?
) {
    val port get() = uri.port
    val originalHost: String get() = uri.host.removeSurrounding("[", "]")
    fun matches(profile: Profile) = CloudflareProfile.snapshot(profile) == snapshot
    /** The relay version is ephemeral and must never be persisted. TLS stays end-to-end in the core. */
    fun testProfile(relayPort: Int, downloadRelayPort: Int? = null): Profile =
        rewrite("127.0.0.1", relayPort, downloadRelayPort)
    fun profileFor(ip: String?): Profile {
        if (ip == null) return CloudflareProfile.restore(snapshot)
        require(CfAddress.literal(ip) != null)
        return rewrite(ip, port, null)
    }
    private fun rewrite(address: String, port: Int, downloadRelayPort: Int?): Profile {
        require(port in 1..65535)
        val q = query.toMutableMap().apply { put("sni", sni); put("host", host) }
        if (downloadRelayPort != null) {
            require(download != null && downloadRelayPort in 1..65535)
            val extra = JsonParser.parseString(q.getValue("extra")).asJsonObject
            extra.getAsJsonObject("downloadSettings").apply {
                addProperty("address", "127.0.0.1"); addProperty("port", downloadRelayPort)
            }
            q["extra"] = extra.toString()
        }
        val authority = if (':' in address) "[$address]" else address
        val link = "${uri.scheme}://${uri.rawUserInfo}@$authority:$port${uri.rawPath.orEmpty()}?" +
            q.entries.joinToString("&") { "${CloudflareProfile.enc(it.key)}=${CloudflareProfile.enc(it.value)}" } +
            (uri.rawFragment?.let { "#$it" } ?: "")
        val original = CloudflareProfile.restore(snapshot)
        return original.copy(kernelConfig = KernelConfig.Vpn((original.kernelConfig as KernelConfig.Vpn).config.copy(uri = link)))
    }
}

/** Original subscription identity and credentials stay local and are never sent to an IP-list service. */
data class CfSavedAddresses(val source: String, val results: List<CfResult>, val selectedIp: String? = null) {
    /** A changed subscription invalidates old measurements, but must retain the Original choice. */
    fun forCurrent(current: Profile): CfSavedAddresses? = if (accepts(current)) this else fromPinned(current)
    fun accepts(current: Profile): Boolean = runCatching {
        val original = CloudflareProfile.restore(source)
        val plan = CloudflareProfile.plan(original)
        val named = current.copy(name = original.name)
        plan.matches(named) || CloudflareProfile.snapshot(plan.profileFor(selectedIp)) == CloudflareProfile.snapshot(named)
    }.getOrDefault(false)
    fun effectiveIp(current: Profile): String? =
        if (source == CloudflareProfile.snapshot(current.copy(name = CloudflareProfile.restore(source).name))) null else selectedIp
    companion object {
        fun fromPinned(current: Profile): CfSavedAddresses? = runCatching {
            if (!current.keepSubscriptionAddress || current.subscriptionId == null) return null
            val ip = CloudflareProfile.originalAddress(current)
            if (CfAddress.literal(ip) == null) return null
            val baseline = current.subscriptionKernelBaseline ?: return null
            val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
            val original = current.copy(kernelConfig = gson.fromJson(baseline, KernelConfig::class.java))
            if (CloudflareProfile.originalAddress(original) == ip) return null
            val plan = CloudflareProfile.plan(original)
            if (CloudflareProfile.snapshot(plan.profileFor(ip)) != CloudflareProfile.snapshot(current)) return null
            CfSavedAddresses(plan.snapshot, emptyList(), ip)
        }.getOrNull()
    }
}

data class CfSettings(val count: Int = 128, val parallelism: Int = 3, val timeoutSeconds: Int = 5, val samples: Int = 3,
    val quick: Boolean = true, val target: Int = 3, val tcpParallelism: Int = 16, val budgetSeconds: Int = 90) {
    init {
        require(count in 1..CfAddress.MAX_CANDIDATES && parallelism in 1..4 && timeoutSeconds in 2..15 && samples in 2..5)
        require(target in 1..10 && tcpParallelism in 1..32 && budgetSeconds in 15..1800)
    }
}
data class CfResult(val ip: String, val times: List<Long>, val attempts: Int, val failedStage: String? = null) {
    val success get() = times.size
    val medianMs get() = times.sorted().let { if (it.isEmpty()) Long.MAX_VALUE else it[it.size / 2] }
    val usable get() = attempts >= 2 && success == attempts && failedStage == null
    companion object {
        val order = compareByDescending<CfResult> { it.success.toDouble() / it.attempts.coerceAtLeast(1) }.thenBy { it.medianMs }
    }
}
