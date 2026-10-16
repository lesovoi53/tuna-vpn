package com.wireturn.app

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import com.wireturn.app.domain.*
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.kernel.NativeConfigCheck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.net.URI

/** Test APK only. Parser/config contracts, not reachability or tunnel acceptance.
 * Never imports profiles, changes settings, launches activities or starts a VPN.
 * NativeConfigCheck creates/deletes only its own private temporary config files.
 * All output is counters, fixed case labels and exception class names; never exception messages.
 */
class FeatureAcceptanceRunner : Instrumentation() {
    private var arguments = Bundle()
    private val report = JSONObject()
    private val failures = JSONArray()
    private var checks = 0
    private val gson = GsonBuilder().registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try { runBlocking { audit() } }
        catch (error: Throwable) { failure("audit", error) }
        report.put("checks", checks).put("failures", failures)
            .put("serverReachabilityTested", false).put("persistentWritesRequested", false)
        finish(if (failures.length() == 0) 0 else 1, Bundle().apply { putString("result", report.toString()) })
    }

    private fun failure(label: String, error: Throwable) {
        failures.put(JSONObject().put("case", label).put("errorClass", error.javaClass.simpleName))
    }

    private suspend fun verify(label: String, block: suspend () -> Unit) {
        checks++
        try { block() } catch (error: Exception) { failure(label, error) }
    }

    private suspend fun audit() {
        val prefs = AppPreferences(targetContext)
        val profiles = prefs.profilesFlow.first()
        val groups = prefs.connectionGroupsFlow.first()
        val subscriptions = prefs.subscriptionsFlow.first()
        val selectedId = prefs.currentProfileIdFlow.first()
        val activeGroup = prefs.activeConnectionGroupFlow.first()
        report.put("storedProfileCount", profiles.size).put("storedGroupCount", groups.size)
        verify("history_roundtrip") { auditHistory() }
        verify("subscription_rule_overrides") { auditRuleOverrides() }
        verify("compressed_import_roundtrip") { auditCompressedImport() }

        val fixture = "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443"
        val fixtures = mutableListOf<Pair<String, VpnConfig>>()
        for (engine in listOf("xray", "sing-box")) {
            for (transport in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
                val extras = when (transport) {
                    "ws", "httpupgrade" -> "&host=fixture.invalid&path=%2Faudit"
                    "grpc" -> "&serviceName=audit"
                    "xhttp" -> "&mode=auto&path=%2Faudit"
                    else -> ""
                }
                fixtures += "${engine}_$transport" to VpnConfig("$fixture?security=tls&type=$transport&sni=fixture.invalid&fp=chrome$extras", engine)
            }
            fixtures += "${engine}_tcp_none" to VpnConfig("$fixture?security=none&type=tcp", engine)
        }
        for (mode in listOf("tls", "tcp", "both")) {
            fixtures += "fragment_$mode" to VpnConfig("$fixture?security=tls&type=tcp&sni=fixture.invalid", "xray",
                VpnOptions(fragmentation = VpnFragmentation(mode, "3-3", "0-1")))
        }
        fixtures += "snell6_default" to VpnConfig("snell://fixture-psk@192.0.2.1:27305/?version=6&mode=default&reuse=true&udp-relay=true")
        fixtures += "snell6_quic_config_only" to VpnConfig("snell://fixture-psk@192.0.2.1:27305/?version=6&mode=default&quic-proxy-mode=true")
        report.put("nativeFixtureCount", fixtures.size)
        fixtures.forEach { (label, config) -> verify("native_$label") { NativeConfigCheck.check(targetContext, config) } }

        verify("escaped_snell_import") {
            val config = requireNotNull(VpnConfig.parse("snell://fixture\\_psk\\@192.0.2.1:27305/?version=6&mode=default"))
            check(!config.uri.contains('\\'))
            NativeConfigCheck.check(targetContext, config)
        }
        verify("fragment_without_tls_rejected") {
            check(runCatching { VpnConfig("$fixture?security=none&type=tcp", "xray",
                VpnOptions(fragmentation = VpnFragmentation("both", "3-3", "0-1"))).validate() }.isFailure)
        }
        auditProfiles("stored", profiles, arguments.getString("nativeCurrent", "true") == "true")

        arguments.getString("subscriptionUrl")?.let { privateUrl ->
            verify("subscription_download_parse") {
                val uri = URI(privateUrl)
                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null)
                val proxy = if (arguments.getString("subscriptionRoute") == "direct") Proxy.NO_PROXY else activeLocalSocksProxy()
                val response = SubscriptionDownload.fetch(privateUrl, proxy, proxy != Proxy.NO_PROXY,
                    "TUNA-VPN/FeatureAcceptance", totalTimeoutMs = 30_000)
                check(response.code == 200)
                val parsed = parseReadOnly(response.bytes.toString(Charsets.UTF_8))
                require(parsed.isNotEmpty())
                report.put("downloadedProfileCount", parsed.size)
                auditProfiles("downloaded", parsed, arguments.getString("nativeSubscription", "true") == "true")
            }
        }
        verify("stored_data_unchanged") {
            check(gson.toJson(profiles) == gson.toJson(prefs.profilesFlow.first()))
            check(gson.toJson(groups) == gson.toJson(prefs.connectionGroupsFlow.first()))
            check(gson.toJson(subscriptions) == gson.toJson(prefs.subscriptionsFlow.first()))
            check(selectedId == prefs.currentProfileIdFlow.first())
            check(activeGroup == prefs.activeConnectionGroupFlow.first())
        }
        report.put("storedDataUnchanged", failures.toString().contains("stored_data_unchanged").not())
    }

    private suspend fun auditProfiles(scope: String, profiles: List<Profile>, native: Boolean) {
        val protocols = JSONObject()
        var checkedNative = 0
        var editors = 0
        profiles.forEachIndexed { index, profile ->
            val config = (profile.kernelConfig as? KernelConfig.Vpn)?.config ?: return@forEachIndexed
            // Protocol names themselves come from a fixed allowlist; unknown source strings are never echoed.
            val protocol = config.protocol.takeIf { it in setOf("vless", "snell", "trojan", "shadowsocks", "hysteria2", "tuic", "mieru", "anytls", "naive", "wireguard", "awg", "trusttunnel") } ?: "other"
            protocols.put(protocol, protocols.optInt(protocol) + 1)
            if (protocol in setOf("vless", "snell")) {
                verify("${scope}_${index}_config") {
                    config.validate()
                    if (native) { NativeConfigCheck.check(targetContext, config); checkedNative++ }
                }
                verify("${scope}_${index}_editor_declared") {
                    val activity = KernelRegistry.get(profile.kernelVariant).configActivityClass
                    @Suppress("DEPRECATION")
                    val info = targetContext.packageManager.getActivityInfo(ComponentName(targetContext, activity), 0)
                    check(info.enabled)
                    editors++
                }
            }
        }
        report.put("${scope}Protocols", protocols).put("${scope}NativeAccepted", checkedNative).put("${scope}EditorsDeclared", editors)
    }

    /** Use production decoders without invoking ProfileManager or committing the parsed bundle.
     * Metadata comments are not applied because this is endpoint validation, not an import transaction.
     */
    private fun parseReadOnly(input: String, depth: Int = 0): List<Profile> {
        require(depth <= 3 && input.toByteArray().size <= ProfileEncoder.MAX_DECODED_BYTES)
        val text = input.trim()
        GroupUriTransport.extract(text)?.let { envelope ->
            val ordinary = if (envelope.ordinaryText.isBlank()) emptyList() else parseReadOnly(envelope.ordinaryText, depth + 1)
            return GroupUriTransport.mergeProfiles(envelope, ordinary)
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            val root = JsonParser.parseString(text)
            return if (root.isJsonObject && root.asJsonObject.has("schema")) {
                TunaSubscriptionDocument.parse(text, GroupUriTransport.capabilities).profiles
                    .map { gson.fromJson(it, Profile::class.java).sanitize() }
            } else LegacyProfileImport.parse(text, gson).also { check(it.skippedEntries == 0) }.profiles
        }
        if (!text.contains("://")) {
            val decoded = ProfileEncoder.decode(text) ?: String(android.util.Base64.decode(text, android.util.Base64.DEFAULT), Charsets.UTF_8)
            return parseReadOnly(decoded, depth + 1)
        }
        return text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.mapIndexed { index, line ->
            if (line.startsWith("tuna://") || line.startsWith("wireturn://") || line.startsWith("wt://")) {
                val preview = previewDeepLink(line) as? DeepLinkPreview.Profiles ?: error("Container rejected")
                LegacyProfileImport.parse(preview.json, gson).also { check(it.skippedEntries == 0) }.profiles
            } else {
                val (_, config) = requireNotNull(KernelRegistry.decodeUri(line))
                listOf(Profile(id = "audit-source-$index", name = "Audit", kernelConfig = config))
            }
        }.flatten().toList()
    }

    private fun auditCompressedImport() {
        val json = """{"profiles":[{"id":"audit-import","name":"Audit","uri":"vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=none&type=tcp"}]}"""
        val encoded = ProfileEncoder.encode(json)
        check(ProfileEncoder.decode(encoded) == json)
        val preview = previewDeepLink("tuna://$encoded") as? DeepLinkPreview.Profiles
        check(preview?.count == 1)
        check(parseReadOnly(encoded).size == 1)
    }

    private fun auditRuleOverrides() {
        val remote = RoutingRule("domain:remote.invalid", RouteAction.BLOCK)
        val own = RoutingRule("domain:own.invalid", RouteAction.DIRECT)
        val source = RoutingPolicy(rules = listOf(remote))
        val overrides = SubscriptionRuleOverrides(userRules = listOf(own)).toggle(remote, false)
        val restored = SubscriptionRuleOverrides.load(overrides.toJson())
        check(restored.apply(source).rules == listOf(own, remote.copy(enabled = false)))
        check(restored.resetSource().apply(source).rules == listOf(own, remote))
        check(SubscriptionRuleOverrides.effectivePolicy(restored.effectiveJson(source))?.rules == restored.apply(source).rules)
    }

    private fun auditHistory() {
        val profile = Profile("audit-history", "Audit", KernelConfig.Vpn(VpnConfig(
            "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=none&type=tcp")))
        val url = SavedProbeResult(profile.id, ProbeKind.URL, probeConfigDigest(profile), 1000, latencyMs = 25)
        val speed = SavedProbeResult(profile.id, ProbeKind.SPEED, probeConfigDigest(profile), 2000, bytesPerSecond = 1_000_000.0)
        val merged = mergeProbeHistory(mergeProbeHistory(emptyList(), url), speed)
        val restored = Gson().fromJson(Gson().toJson(merged), Array<SavedProbeResult>::class.java).toList()
        check(restored.latestProbe(profile, ProbeKind.URL) == url)
        check(restored.latestProbe(profile, ProbeKind.SPEED) == speed)
        check(!url.isStale(profile.copy(name = "Renamed")))
        check(url.isStale(profile.copy(kernelConfig = KernelConfig.Vpn(VpnConfig(
            "vless://00000000-0000-4000-8000-000000000001@192.0.2.2:443?security=none&type=tcp")))))
        check(!Gson().toJson(restored).contains("192.0.2.1"))
    }
}
