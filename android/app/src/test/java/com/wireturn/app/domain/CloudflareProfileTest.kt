package com.wireturn.app.domain

import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnOptions
import com.wireturn.app.kernel.XrayVpnConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import kotlin.random.Random

class CloudflareProfileTest {
    @Test fun xhttpTlsMixedAlpnIsAcceptedWithoutChangingItsProtocols() {
        listOf("h2,http/1.1,h3", "h3,h2,http/1.1", "h3,h2", "h2", "http/1.1").forEachIndexed { i, alpn ->
            val link = base.replace("type=ws", "type=xhttp").substringBefore('#') + "&alpn=${CloudflareProfile.enc(alpn)}"
            val plan = CloudflareProfile.plan(profile(link))
            assertEquals(alpn, CloudflareProfile.query(uri(plan.profileFor("104.16.1.1")))["alpn"])
            assertEquals(alpn, CloudflareProfile.query(uri(plan.testProfile(19090)))["alpn"])
            val output = java.io.File("build/release-fixtures").apply { mkdirs() }
            val wrapper = XrayVpnConfig.generate((plan.testProfile(19090).kernelConfig as KernelConfig.Vpn).config,
                "127.0.0.1:19091", "fixture", "fixture", false)
            java.io.File(output, "cf-alpn-$i.json").writeText(wrapper.toString())
        }
    }
    @Test fun onlyH3ExplainsUdpRestrictionAndStillShowsOriginalAddress() {
        val original = profile(base.replace("type=ws", "type=xhttp").substringBefore('#') + "&alpn=h3")
        assertEquals(CfUnavailable.HTTP3, assertThrows(CfProfileException::class.java) { CloudflareProfile.plan(original) }.reason)
        assertEquals("original.example", CloudflareProfile.originalAddress(original))
    }
    @Test fun splithttpAlpnAliasMatchesXhttpAndAnOmittedListStaysSupported() {
        val alias = profile(base.replace("type=ws", "type=splithttp"))
        assertEquals("original.example", CloudflareProfile.plan(alias).originalHost)
        assertFalse(CloudflareProfile.usesHttp3(emptyList()))
        assertFalse(CloudflareProfile.usesHttp3(listOf("h2", "h3")))
        assertTrue(CloudflareProfile.usesHttp3(listOf("h3")))
    }
    private val base = "vless://00000000-0000-4000-8000-000000000001@original.example:443/?type=ws&security=tls&sni=tls.example&host=http.example&path=%2Fa%2Bb%3Fid%3D2&fp=firefox&encryption=none#Test%20CF"
    private fun profile(link: String = base) = Profile("id", "Москва [CF]", KernelConfig.Vpn(VpnConfig(link)),
        subscriptionId = "subscription", subscriptionSourceId = "source", subscriptionOriginalName = "Москва [CF]", subscriptionUriIdentity = true)
    private fun uri(profile: Profile) = URI((profile.kernelConfig as KernelConfig.Vpn).config.uri)
    @Test fun tagRequiresStandaloneLatinWordInAnyPosition() {
        listOf("CF", "cf Москва", "Москва cf", "москва [Cf] 3", "cf-сервер", "🥷 CF ⚡").forEach { assertTrue(it, CloudflareProfile.tagged(it)) }
        listOf("cf1", "1cf", "mycf", "CFМосква", "москваcf", "Cloudflare", "_cf_", "cf\u0301", "сf", "сф").forEach { assertFalse(it, CloudflareProfile.tagged(it)) }
    }
    @Test fun addressSelectionKeepsIdentityAndEveryOtherConnectionParameter() {
        val original = profile(); val snapshot = CloudflareProfile.snapshot(original)
        val plan = CloudflareProfile.plan(original)
        val selected = plan.profileFor("104.16.1.1")
        assertEquals("104.16.1.1", uri(selected).host)
        assertEquals(CloudflareProfile.query(uri(original)), CloudflareProfile.query(uri(selected)))
        assertEquals(original.id, selected.id); assertEquals(original.name, selected.name)
        assertEquals(original.subscriptionId, selected.subscriptionId)
        assertEquals(original.subscriptionSourceId, selected.subscriptionSourceId)
        assertEquals(original.subscriptionUriIdentity, selected.subscriptionUriIdentity)
        assertEquals(snapshot, CloudflareProfile.snapshot(plan.profileFor(null)))
        assertEquals(snapshot, CloudflareProfile.snapshot(original))
    }
    @Test fun implicitHostAndSniSurviveIpReplacement() {
        val plan = CloudflareProfile.plan(profile(base.replace("&sni=tls.example&host=http.example", "")))
        val q = CloudflareProfile.query(uri(plan.profileFor("104.16.1.1")))
        assertEquals("original.example", q["sni"]); assertEquals("original.example", q["host"])
    }
    @Test fun ipv6AndEncodedTrojanPasswordArePreserved() {
        val original = profile(base.replace("vless://00000000-0000-4000-8000-000000000001", "trojan://a%2Bb%40c%3Ad").replace("&encryption=none", ""))
        val changed = CloudflareProfile.plan(original).profileFor("2606:4700::1234")
        assertEquals("a%2Bb%40c%3Ad", uri(changed).rawUserInfo)
        assertEquals("[2606:4700::1234]", uri(changed).host)
        val json = XrayVpnConfig.generate((changed.kernelConfig as KernelConfig.Vpn).config, "127.0.0.1:12345", "u", "p", false)
        assertEquals("a+b@c:d", json.getAsJsonObject("outbound_patch").getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject["password"].asString)
    }
    @Test fun relayAddressNeverAppearsInSavedSelection() {
        val plan = CloudflareProfile.plan(profile())
        val temp = plan.testProfile(12345)
        assertEquals("127.0.0.1", uri(temp).host); assertEquals(12345, uri(temp).port)
        val selected = plan.profileFor("104.16.2.3")
        assertEquals(443, uri(selected).port)
        XrayVpnConfig.generate((temp.kernelConfig as KernelConfig.Vpn).config, "127.0.0.1:12346", "u", "p", false)
    }
    @Test fun downloadChannelUsesSeparateRelayAndSavedSourceStaysUnchanged() {
        val extra = """{"xPaddingBytes":"50-150","downloadSettings":{"address":"download.example","port":443,"network":"xhttp","security":"tls","tlsSettings":{"serverName":"down-tls.example","alpn":["h2","http/1.1","h3"]},"xhttpSettings":{"host":"down-http.example","path":"/download","mode":"stream-one"}}}"""
        val original = profile(base.replace("type=ws", "type=xhttp") + "")
        val config = (original.kernelConfig as KernelConfig.Vpn).config
        val p = original.copy(kernelConfig = KernelConfig.Vpn(config.copy(uri = config.uri.substringBefore('#') + "&mode=packet-up&extra=${CloudflareProfile.enc(extra)}")))
        val plan = CloudflareProfile.plan(p)
        assertEquals(CfEndpoint("download.example", 443), plan.download)
        val q = CloudflareProfile.query(uri(plan.testProfile(11111, 22222)))
        val rewritten = JsonParser.parseString(q.getValue("extra")).asJsonObject
        assertEquals("127.0.0.1", rewritten.getAsJsonObject("downloadSettings")["address"].asString)
        assertEquals(22222, rewritten.getAsJsonObject("downloadSettings")["port"].asInt)
        assertEquals(extra, CloudflareProfile.query(uri(plan.profileFor("104.16.1.1")))["extra"])
        assertEquals("down-tls.example", rewritten.getAsJsonObject("downloadSettings").getAsJsonObject("tlsSettings")["serverName"].asString)
    }
    @Test fun unsafeNativeDestinationOverridesAreRejected() {
        val p = profile().copy(kernelConfig = KernelConfig.Vpn(VpnConfig(base, options = VpnOptions(xray = JsonParser.parseString("""{"settings":{"vnext":[]}}""").asJsonObject))))
        assertEquals(CfUnavailable.OVERRIDE, assertThrows(CfProfileException::class.java) { CloudflareProfile.plan(p) }.reason)
    }
    @Test fun incompatibleProfilesAreExplainedNotConverted() {
        assertEquals(CfUnavailable.TRANSPORT, assertThrows(CfProfileException::class.java) { CloudflareProfile.plan(profile(base.replace("type=ws", "type=tcp"))) }.reason)
        assertEquals(CfUnavailable.PORT, assertThrows(CfProfileException::class.java) { CloudflareProfile.plan(profile(base.replace(":443/", ":12345/"))) }.reason)
        assertEquals(CfUnavailable.PROTOCOL, assertThrows(CfProfileException::class.java) { CloudflareProfile.plan(Profile("id", "CF")) }.reason)
    }
    @Test fun refreshRestoresOriginalAndOldChoicesRejectChangedCredentials() {
        val original = profile(); val plan = CloudflareProfile.plan(original)
        val record = CfSavedAddresses(plan.snapshot, listOf(CfResult("104.16.1.1", listOf(10, 20, 30), 3)), "104.16.1.1")
        assertTrue(record.accepts(plan.profileFor("104.16.1.1")))
        assertTrue(record.accepts(original)); assertNull(record.effectiveIp(original))
        assertTrue(record.accepts(plan.profileFor("104.16.1.1").copy(name = "Another CF name")))
        assertFalse(record.accepts(profile(base.replace("http.example", "different.example"))))
        assertFalse(record.accepts(original.copy(subscriptionId = "another")))
    }
    @Test fun numericParsingNeverResolvesHostnamesOrAcceptsAmbiguousOctets() {
        listOf("localhost", "cloudflare.com", "127.1", "0x7f000001", "1.2.3.256", "001.2.3.4", "fe80::1%wlan0").forEach { assertNull(it, CfAddress.literal(it)) }
        assertNotNull(CfAddress.literal("104.16.1.1")); assertNotNull(CfAddress.literal("2606:4700::1"))
    }
    @Test fun boundedCandidateSetCoversRangesWithoutDuplicatesOrNonCfAddresses() {
        val result = CfAddress.candidates(128, priority = listOf("104.16.1.1", "104.16.1.1", "127.0.0.1", "example.com"), random = Random(10))
        assertEquals(128, result.size); assertEquals(result.size, result.distinct().size)
        assertEquals("104.16.1.1", result.first())
        assertTrue(result.all { ip -> CfAddress.bundled.any { it.contains(CfAddress.literal(ip)!!) } })
        assertFalse(result.any { ':' in it })
        assertTrue(CfAddress.candidates(128, ipv6 = true, random = Random(1)).any { ':' in it })
    }
    @Test fun smallRangeTerminatesEvenWhenRequestedCountIsLarger() {
        assertEquals(1, CfAddress.candidates(128, listOf(CfAddress.range("104.16.1.1/32")!!)).size)
        assertNull(CfAddress.range("104.16.1.1/13")); assertNull(CfAddress.range("0.0.0.0/0"))
    }
    @Test fun reliabilityRanksAboveOneFastSuccess() {
        val stable = CfResult("a", listOf(100, 150, 200), 3)
        val flaky = CfResult("b", listOf(1), 3)
        assertEquals(stable, listOf(flaky, stable).sortedWith(CfResult.order).first())
        assertTrue(stable.usable); assertFalse(flaky.usable); assertEquals(150L, stable.medianMs)
    }
    @Test fun invalidSearchBoundsAreRejected() {
        assertEquals(5000, CfSettings(count = 5000).count)
        assertThrows(IllegalArgumentException::class.java) { CfSettings(count = 5001) }
        assertThrows(IllegalArgumentException::class.java) { CfSettings(parallelism = 20) }
        assertThrows(IllegalArgumentException::class.java) { CfSettings(samples = 1) }
    }
    @Test fun emitsNativeFixturesForSupportedTransportsWithNoRealCredentials() {
        val output = java.io.File("build/release-fixtures").apply { mkdirs() }
        listOf("ws", "grpc", "httpupgrade", "xhttp").forEach { transport ->
            val plan = CloudflareProfile.plan(profile(base.replace("type=ws", "type=$transport")))
            listOf("relay" to plan.testProfile(19090), "selected" to plan.profileFor("104.16.1.1")).forEach { (stage, p) ->
                val wrapper = XrayVpnConfig.generate((p.kernelConfig as KernelConfig.Vpn).config, "127.0.0.1:19091", "fixture", "fixture", false)
                java.io.File(output, "cf-$transport-$stage.json").writeText(wrapper.toString())
            }
        }
    }
}
