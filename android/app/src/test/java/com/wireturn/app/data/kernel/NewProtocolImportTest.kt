package com.wireturn.app.data.kernel

import com.google.gson.JsonObject
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.kernel.SingBoxConfig
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater

class NewProtocolImportTest {
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 251.toByte() })
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 41 })
    private val ini = """
        # test
        [Interface]
        PrivateKey = $privateKey
        Address = 10.67.67.2/32
        Jc = 5
        Jmin = 50
        Jmax = 150
        S1 = 40
        S2 = 120
        S3 = 12
        S4 = 12
        H1 = 1847293
        H2 = 839102847
        H3 = 49182736
        H4 = 129384756
        RandomTrailers = true
        DisableCookies = true
        [Peer]
        PublicKey = $publicKey
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()
    private fun vpn(bytes: ByteArray) = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun compressed(value: String): ByteArray {
        val bytes = value.toByteArray(); val d = Deflater(8); d.setInput(bytes); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return ByteBuffer.allocate(4).putInt(bytes.size).array() + out.toByteArray()
    }
    @Test fun awgIniRetainsNameKeysAndAdvancedFlags() {
        val p = VpnConfig.parse(vpn(ini.toByteArray()))
        assertNotNull("AWG INI must import", p)
        assertEquals("test", p!!.displayName)
        assertEquals("awg", p.protocol)
        assertEquals(privateKey, p.outbound()["private_key"].asString)
        assertTrue(p.outbound().getAsJsonObject("amnezia")["random_trailers"].asBoolean)
        assertTrue(p.outbound().getAsJsonObject("amnezia")["disable_cookies"].asBoolean)
        assertTrue(KernelConfig.parseUri(p.uri) is KernelConfig.Vpn)
    }
    @Test fun awgIniUsesNameImmediatelyBeforeFirstPeer() {
        val panelIni = ini.removePrefix("# test\n").replace("[Peer]", "# Москва AWG\n[Peer]")
        val p = VpnConfig.parse(vpn(panelIni.toByteArray()))!!
        assertEquals("Москва AWG", p.displayName)
        assertEquals(VpnConfig.parse(vpn(ini.toByteArray()))!!.outbound(), p.outbound())
        assertEquals("Explicit", VpnConfig.parse(vpn(panelIni.toByteArray()) + "#Explicit")!!.displayName)
    }
    @Test fun awgIniDoesNotUseOrdinaryFieldCommentsAsName() {
        val panelIni = ini.removePrefix("# test\n").replace("Jc = 5", "# Noise packet count\nJc = 5")
        assertEquals("", AwgUri.parse(AwgUri.normalizeAll(vpn(panelIni.toByteArray())).single()).name)
    }
    @Test fun wgQueryDoesNotTurnLiteralPlusIntoSpace() {
        val p = VpnConfig.parse("wg://192.0.2.1:51820?private_key=$privateKey&public_key=$publicKey&local_address=10.67.67.2/32&enable_amnezia=true&jc=5")
        assertNotNull(p)
        assertEquals(privateKey, p!!.outbound()["private_key"].asString)
        assertEquals("awg", p.protocol)
    }
    private fun multiPeerUri(): String {
        val secondKey = Base64.getEncoder().encodeToString(ByteArray(32) { 43 })
        val text = ini + "\n[Peer]\nPublicKey = $secondKey\nEndpoint = 192.0.2.2:51821\nAllowedIPs = 10.99.0.0/16\n"
        return AwgUri.normalizeAll(vpn(text.toByteArray())).single()
    }
    @Test fun awgEditorPrimaryPeerChangesRetainOtherPeers() {
        val original = multiPeerUri()
        val before = AwgUri.parse(original).endpoint.getAsJsonArray("peers")
        val draft = VpnEditorDraft.from(original)
        val keepalive = draft.fields.first { it.queryName == "persistent_keepalive_interval" }
        val edited = draft.change("server", "192.0.2.9").change("port", "51999").change(keepalive.key, "31").toUri()
        val after = AwgUri.parse(edited).endpoint.getAsJsonArray("peers")
        assertEquals(2, after.size())
        assertEquals("192.0.2.9", after[0].asJsonObject["address"].asString)
        assertEquals(51999, after[0].asJsonObject["port"].asInt)
        assertEquals(31, after[0].asJsonObject["persistent_keepalive_interval"].asInt)
        assertEquals(before[1], after[1])
    }
    @Test fun awgEditorPeerListUpdatesPrimaryFieldsWithoutLosingNetworkSettings() {
        val original = multiPeerUri()
        val before = AwgUri.parse(original)
        val peers = before.endpoint.getAsJsonArray("peers").deepCopy()
        peers[0].asJsonObject.addProperty("address", "2001:db8::9")
        peers[0].asJsonObject.addProperty("port", 51999)
        val draft = VpnEditorDraft.from(original)
        val edited = draft.change(draft.fields.first { it.queryName == "peers" }.key, peers.toString()).toUri()
        val after = AwgUri.parse(edited)
        assertEquals(51999, java.net.URI(edited).port)
        assertEquals(peers[1], after.endpoint.getAsJsonArray("peers")[1])
        assertEquals(before.endpoint["private_key"], after.endpoint["private_key"])
        assertEquals(before.endpoint["amnezia"], after.endpoint["amnezia"])
        assertEquals(before.endpoint["address"], after.endpoint["address"])
        assertEquals(before.dns, after.dns)
    }
    @Test fun awgEditorConflictingExplicitPeerEditsAreRejected() {
        val original = multiPeerUri()
        val draft = VpnEditorDraft.from(original)
        assertEquals(original, draft.toUri())
        val peers = AwgUri.parse(original).endpoint.getAsJsonArray("peers").deepCopy()
        peers[0].asJsonObject.addProperty("address", "192.0.2.8")
        val edited = draft.change("server", "192.0.2.9")
            .change(draft.fields.first { it.queryName == "peers" }.key, peers.toString()).toUri()
        assertNull("Conflicting edits must not silently overwrite each other", VpnConfig.parse(edited))
    }
    @Test fun amneziaContainerAndIniProduceSameConnection() {
        val last = JsonObject().apply { addProperty("config", ini) }
        val awg = JsonObject().apply { addProperty("last_config", last.toString()) }
        val container = JsonObject().apply { addProperty("container", "amnezia-awg"); add("awg", awg) }
        val root = JsonObject().apply { add("containers", com.google.gson.JsonArray().apply { add(container) }) }
        val packed = VpnConfig.parse(vpn(compressed(root.toString())))
        val plain = VpnConfig.parse(vpn(ini.toByteArray()))
        assertNotNull(packed); assertNotNull(plain)
        assertEquals(plain!!.outbound(), packed!!.outbound())
        val config = SingBoxConfig.generate(packed, "127.0.0.1:1080", allowNoAuth = true)
        assertEquals("wireguard", config.getAsJsonArray("endpoints")[0].asJsonObject["type"].asString)
        assertFalse(config.has("outbounds"))
    }
    @Test fun snellSixKeepsRealWireVersionAndMode() {
        val p = VpnConfig.parse("snell://test-psk@192.0.2.1:32401?version=6&udp-relay=true&mode=default#Snell6")
        assertNotNull(p)
        assertEquals(6, p!!.outbound()["version"].asInt)
        assertEquals("default", p.outbound()["mode"].asString)
        assertFalse(p.outbound().has("obfs_mode"))
    }
    @Test fun readableTrustTunnelPreservesCredentialsAndVerifiesTls() {
        val p = VpnConfig.parse("tt://user:p%2B%3A%40ss@vpn.invalid:443?security=tls&sni=edge.invalid#TrustTunnel")
        assertNotNull(p)
        val out = p!!.outbound()
        assertEquals("trusttunnel", out["type"].asString)
        assertEquals("p+:@ss", out["password"].asString)
        assertEquals("edge.invalid", out.getAsJsonObject("tls")["server_name"].asString)
        assertFalse(out.getAsJsonObject("tls").has("insecure"))
    }

    private fun tlv(vararg fields: Pair<Int, ByteArray>): String {
        fun n(value: Int): ByteArray = if (value < 64) byteArrayOf(value.toByte()) else byteArrayOf((0x40 or (value shr 8)).toByte(), value.toByte())
        val out = java.io.ByteArrayOutputStream()
        fields.forEach { (tag, bytes) -> out.write(n(tag)); out.write(n(bytes.size)); out.write(bytes) }
        return "tt://?" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }
    private fun ttFields() = arrayOf(0 to byteArrayOf(1), 1 to "edge.invalid".toByteArray(), 2 to "edge.invalid:443".toByteArray(),
        5 to "user".toByteArray(), 6 to "p+:@ss".toByteArray())
    @Test fun officialTrustTunnelTlvKeepsQuicPrefixNameAndCredentials() {
        val p = VpnConfig.parse(tlv(*ttFields(), 9 to byteArrayOf(2), 11 to "352665e0".toByteArray(), 12 to "Test NED".toByteArray(), 63 to byteArrayOf(42)))
        assertNotNull(p); assertEquals("Test NED", p!!.displayName)
        assertTrue(p.outbound()["quic"].asBoolean)
        assertEquals("352665e0", p.outbound()["client_random_prefix"].asString)
        assertEquals("p+:@ss", p.outbound()["password"].asString)
    }
    @Test fun officialTrustTunnelLastSingularWinsAndMalformedLengthFails() {
        val p = VpnConfig.parse(tlv(*ttFields(), 12 to "Old".toByteArray(), 12 to "New".toByteArray()))
        assertEquals("New", p!!.displayName)
        assertNull(VpnConfig.parse("tt://?AQVh"))
    }
    @Test fun incompatibleChromeTlsOptionsAreRejected() {
        val base = "tt://user:pass@edge.invalid:443?use_cronet_https=true"
        assertNotNull(VpnConfig.parse(base))
        assertNotNull("Explicit certificate verification remains compatible with Chrome", VpnConfig.parse("$base&insecure=false"))
        for (option in listOf("fp=firefox", "insecure=true", "alpn=h2", "client_random_prefix=abcd", "certificate_public_key_sha256=abc"))
            assertNull(option, VpnConfig.parse("$base&$option"))
        assertNull(VpnConfig.parse("tt://user:pass@edge.invalid:443?client_random_prefix=abcd/ff"))
        assertNull(VpnConfig.parse("tt://user:pass@edge.invalid:443?quic=true&fp=firefox"))
    }
    @Test fun snellQuicProxyRequiresSixAndPreservesToggle() {
        assertTrue(VpnConfig.parse("snell://psk@192.0.2.1:1234?version=6&quic-proxy-mode=true")!!.outbound()["quic_proxy_mode"].asBoolean)
        assertNull(VpnConfig.parse("snell://psk@192.0.2.1:1234?version=5&quic-proxy-mode=true"))
        assertNull(VpnConfig.parse("snell://psk@192.0.2.1:1234?version=6&quic-proxy-mode=true&quic_proxy_mode=false"))
    }
    @Test fun awgIdentityIgnoresNameAndEncodingButNotKeys() {
        val p = VpnConfig.parse(vpn(ini.toByteArray()))!!
        assertEquals(AwgUri.identity(p.uri), AwgUri.identity(p.uri.substringBefore('#') + "#Renamed"))
        assertNotEquals(AwgUri.identity(p.uri), AwgUri.identity(VpnConfig.parse(vpn(ini.replace(publicKey, Base64.getEncoder().encodeToString(ByteArray(32) { 42 })).toByteArray()))!!.uri))
    }
    @Test fun awgProfileDnsIsTunneledAndBootstrapIsSeparate() {
        val p = VpnConfig.parse(vpn(ini.replace("Jc = 5", "DNS = 10.67.67.1\nJc = 5").toByteArray()))!!
        val c = SingBoxConfig.generate(p,"127.0.0.1:12345",allowNoAuth=true)
        val dns = c.getAsJsonObject("dns")
        assertEquals("profile-dns-0",dns["final"].asString)
        assertEquals("vpn",dns.getAsJsonArray("servers")[0].asJsonObject["detour"].asString)
        assertEquals("tcp",dns.getAsJsonArray("servers")[0].asJsonObject["type"].asString)
        assertEquals("ipv4_only",dns["strategy"].asString)
        assertEquals("bootstrap",c.getAsJsonObject("route").getAsJsonObject("default_domain_resolver")["server"].asString)
        assertEquals("prefer_ipv4",c.getAsJsonObject("route").getAsJsonObject("default_domain_resolver")["strategy"].asString)
    }
    @Test fun awgRejectsTruncatedKeysAndBadRanges() {
        assertNull(VpnConfig.parse(vpn(ini.replace(privateKey,"abc").toByteArray())))
        assertNull(VpnConfig.parse(vpn(ini.replace("Jmin = 50", "Jmin = 999").toByteArray())))
        assertNull(VpnConfig.parse(vpn(ini.replace("H1 = 1847293", "H1 = 4294967296").toByteArray())))
    }
    @Test fun awgIniBooleanWordsFromPanelsAreAccepted() {
        val p = VpnConfig.parse(vpn(ini.replace("= true", "= on").toByteArray()))!!
        assertTrue(p.outbound().getAsJsonObject("amnezia")["random_trailers"].asBoolean)
        val q = VpnConfig.parse(vpn(ini.replace("= true", "= off").toByteArray()))!!
        assertFalse(q.outbound().getAsJsonObject("amnezia")["random_trailers"].asBoolean)
    }
    @Test fun structuredConfigsNormalizeAwgAndTrustTunnelAsTextImportsDo() {
        val text = vpn(ini.toByteArray())
        assertEquals(VpnConfig.parse(text), VpnConfig(text).sanitize())
        val tt = tlv(*ttFields(), 9 to byteArrayOf(2), 11 to "352665e0".toByteArray())
        assertEquals(VpnConfig.parse(tt), VpnConfig(tt).sanitize())
    }
    @Test fun awgDuplicateBatchKeepsOneAndDoesNotMergeDifferentCredentials() {
        val first = com.wireturn.app.data.Profile("first", "INI", com.wireturn.app.data.KernelConfig.Vpn(VpnConfig.parse(vpn(ini.toByteArray()))!!))
        val second = first.copy(id="second", name="Other format")
        val different = first.copy(id="different", kernelConfig=com.wireturn.app.data.KernelConfig.Vpn(VpnConfig.parse(vpn(ini.replace(publicKey,Base64.getEncoder().encodeToString(ByteArray(32){42})).toByteArray()))!!))
        val kept = com.wireturn.app.domain.AwgProfileDedup.filter(listOf(first,second,different), { it }, "second")
        assertEquals(listOf("second", "different"), kept.map { it.id })
        val preview = com.wireturn.app.domain.previewDeepLink(first.let { (it.kernelConfig as com.wireturn.app.data.KernelConfig.Vpn).config.uri })
        assertTrue(preview is com.wireturn.app.domain.DeepLinkPreview.Profiles)
    }
    @Test fun differentEncodingsOfSameAwgAccountKeepTuningVariantsWithoutDuplicateRows() {
        val first = com.wireturn.app.data.Profile("one", "URI", com.wireturn.app.data.KernelConfig.Vpn(VpnConfig.parse(vpn(ini.toByteArray()))!!))
        val second = first.copy(id="two", kernelConfig=com.wireturn.app.data.KernelConfig.Vpn(VpnConfig.parse(vpn(ini.replace("Jc = 5", "MTU = 1376\nJc = 5").replace("0.0.0.0/0, ::/0", "0.0.0.0/0").toByteArray()))!!))
        val kept = com.wireturn.app.domain.AwgProfileDedup.filter(listOf(first, second), { it }, withProfile={_,p->p})
        assertEquals(1,kept.size)
        val config = (kept.single().kernelConfig as com.wireturn.app.data.KernelConfig.Vpn).config
        assertEquals(1280,config.outbound()["mtu"].asInt)
        assertEquals(1376,VpnConfig.parse(config.importedVariants.single())!!.outbound()["mtu"].asInt)
    }
    @Test fun awgFormatChangeKeepsLocalIdentityAcrossRefresh() {
        val original = VpnConfig.parse(vpn(ini.toByteArray()))!!
        val changed = VpnConfig.parse(vpn(ini.replace("Jc = 5", "MTU = 1376\nJc = 5").toByteArray()))!!
        val before = com.wireturn.app.data.Profile("local-id", "My name", KernelConfig.Vpn(original),
            subscriptionId="sub", subscriptionSourceId="old-format", subscriptionUriIdentity=true)
        val incoming = before.copy(id="new-format", kernelConfig=KernelConfig.Vpn(changed))
        val match = com.wireturn.app.domain.SubscriptionIdentity.match(incoming,listOf(incoming),listOf(before),emptySet())
        assertEquals(before,match)
    }
    @Test fun awgDuplicateAliasesInSubscriptionGroupsResolveToOneProfile() {
        val first = VpnConfig.parse(vpn(ini.toByteArray()))!!
        val second = VpnConfig.parse(vpn(ini.replace("Jc = 5", "MTU = 1376\nJc = 5").toByteArray()))!!
        val profile = com.wireturn.app.data.Profile("local-one","AWG",KernelConfig.Vpn(first),subscriptionId="sub",subscriptionSourceId="one")
        val doc = com.google.gson.JsonParser.parseString("""{"groups":[{"id":"g","name":"WG","category":"VPN","family":"VPN","type":"URL_TEST","memberIds":["one","two"],"routingProfileId":"two","testUrl":"https://example.invalid/test"}]}""").asJsonObject
        doc.add("profiles",com.google.gson.JsonArray().apply {
            listOf("one" to first.uri,"two" to second.uri).forEach { (id,uri) -> add(JsonObject().apply {addProperty("id",id);addProperty("uri",uri)}) }
        })
        val group = com.wireturn.app.domain.SubscriptionGroups.parse(doc,"sub",listOf(profile)).single()
        assertEquals(listOf("local-one"),group.memberIds)
        assertEquals("local-one",group.routingProfileId)
        assertTrue(group.repairRequired)
        assertFalse(group.automatic)
        assertFalse(group.testOnConnect)
    }
    @Test fun awgSavedVariantsSurviveJsonRoundTrip() {
        val first = VpnConfig.parse(vpn(ini.toByteArray()))!!
        val other = VpnConfig.parse(vpn(ini.replace("Jc = 5", "MTU = 1376\nJc = 5").toByteArray()))!!
        val withVariants = first.copy(importedVariants=listOf(other.uri))
        val gson = com.google.gson.Gson()
        assertEquals(withVariants, gson.fromJson(gson.toJson(withVariants),VpnConfig::class.java).sanitize())
        assertTrue(gson.fromJson("{\"uri\":\"\"}",VpnConfig::class.java).sanitize().importedVariants.isEmpty())
    }
}
