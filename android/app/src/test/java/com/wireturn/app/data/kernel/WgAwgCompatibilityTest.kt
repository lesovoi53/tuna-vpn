package com.wireturn.app.data.kernel

import com.wireturn.app.domain.DeepLinkPreview
import com.wireturn.app.domain.previewDeepLink
import com.wireturn.app.domain.parseWgProfileImport
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class WgAwgCompatibilityTest {
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 251.toByte() })
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 41 })
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private val legacy get() = "wireguard://${enc(privateKey)}@192.0.2.1:51820?address=10.67.67.2/32&mtu=1280&publickey=${enc(publicKey)}"
    private val plainIni get() = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.67.67.2/32
        [Peer]
        PublicKey = $publicKey
        Endpoint = 192.0.2.1:51820
    """.trimIndent()
    private val advancedIni get() = plainIni.replace("[Peer]", """
        Jc = 5
        Jmin = 50
        Jmax = 150
        S1 = 40
        S2 = 120
        S3 = 12
        S4 = 12
        H1 = 100-200
        H2 = 300
        H3 = 400
        H4 = 500
        I1 = \<b 0x0102\>\<r 16\>
        I2 = <b 0x0304><r 8>
        I3 = <r 4>
        I4 = <r 5>
        I5 = <r 6>
        HeaderProtectionKey = $privateKey
        ContentPaddingAddition = 8-16
        RekeyAfterTime = 120
        RekeyTimeout = 5
        RejectAfterTime = 180
        KeepaliveTimeout = 10
        MaxHandshakeAttempts = 20
        RandomTrailers = true
        DisableCookies = true
        **Synthetic AWG - ADMIN**
        [Peer]
    """.trimIndent())

    @Test fun legacyUserInfoAndAliasesMatchCanonicalUriAndRawIni() {
        val parsed = VpnConfig.parse(legacy)
        assertNotNull("Legacy WG private key in userinfo must import", parsed)
        assertEquals(privateKey, parsed!!.outbound()["private_key"].asString)
        val ini = VpnConfig.parse(plainIni)
        assertNotNull("Raw INI must import", ini)
        assertEquals(parsed.uri, ini!!.uri)
        assertEquals(AwgUri.accountIdentity(parsed.uri), AwgUri.accountIdentity(ini.uri))
        assertEquals(parseWgProfileImport(legacy)!!.profiles.single(), parseWgProfileImport(plainIni)!!.profiles.single())
        assertNotNull(VpnConfig.parse(legacy.replace(enc(privateKey), privateKey.replace("/", "%2F"))))
    }

    @Test fun conflictingAliasesAndUserInfoAreRejected() {
        assertNull(VpnConfig.parse("$legacy&private_key=${enc(publicKey)}"))
        assertNull(VpnConfig.parse("$legacy&local_address=10.67.67.3/32"))
        assertNull(VpnConfig.parse("$legacy&public_key=${enc(privateKey)}"))
        assertNull(VpnConfig.parse("$legacy&publickey=${enc(privateKey)}"))
    }

    @Test fun rawAdvancedIniRetainsEveryFieldAndOnlyNormalizesPresentation() {
        val parsed = VpnConfig.parse(advancedIni)
        assertNotNull("Raw AWG INI with panel markdown must import", parsed)
        assertEquals("Synthetic AWG - ADMIN", parsed!!.displayName)
        val awg = parsed.outbound().getAsJsonObject("amnezia")
        assertEquals(AwgUri.amneziaFields, awg.keySet())
        assertEquals("<b 0x0102><r 16>", awg["i1"].asString)
        assertEquals("<b 0x0304><r 8>", awg["i2"].asString)
        assertEquals(privateKey, awg["header_protection_key"].asString)
        assertEquals("8-16", awg["content_padding_addition"].asString)
        assertEquals("100-200", awg["h1"].asString)
        mapOf("jc" to "5", "jmin" to "50", "jmax" to "150", "s1" to "40", "s2" to "120",
            "s3" to "12", "s4" to "12", "h2" to "300", "h3" to "400", "h4" to "500",
            "i3" to "<r 4>", "i4" to "<r 5>", "i5" to "<r 6>", "rekey_after_time" to "120",
            "rekey_timeout" to "5", "reject_after_time" to "180", "keepalive_timeout" to "10",
            "max_handshake_attempts" to "20").forEach { (field, value) -> assertEquals(field, value, awg[field].asString) }
        assertTrue(awg["random_trailers"].asBoolean)
        assertTrue(awg["disable_cookies"].asBoolean)
        assertEquals(parsed, VpnConfig.parse(parsed.uri))
        assertTrue(previewDeepLink(advancedIni) is DeepLinkPreview.Profiles)
        val bundle = parseWgProfileImport(advancedIni)!!
        assertEquals(parsed, (bundle.profiles.single().kernelConfig as com.wireturn.app.data.KernelConfig.Vpn).config)
    }

    @Test fun invalidIniCannotSilentlyDropFieldsOrAdditionalInterface() {
        assertNull(VpnConfig.parse(plainIni.replace("Address =", "PostUp = arbitrary\nAddress =")))
        assertNull(VpnConfig.parse(plainIni + "\n" + plainIni))
        assertNull(VpnConfig.parse(plainIni + "\n[Interface]"))
        assertNull(VpnConfig.parse(plainIni.replace("PrivateKey = $privateKey", "PrivateKey = $privateKey\nPrivateKey = $publicKey")))
    }

    @Test fun rawIniDoesNotApplyUriFragmentOrCredentialUnescaping() {
        val marked = advancedIni.replace("I2 = <b 0x0304><r 8>", "I2 = <b 0x0304><r 8>#literal")
        val parsed = VpnConfig.parse(marked)
        assertNotNull(parsed)
        assertEquals("<b 0x0304><r 8>#literal", parsed!!.outbound().getAsJsonObject("amnezia")["i2"].asString)
        assertNull(VpnConfig.parse(plainIni.replace(privateKey, enc(privateKey))))
    }

    @Test fun bomCommentsAndEncodedContainerUseTheSameValidatedFields() {
        val raw = "\uFEFF# Panel export\n$advancedIni"
        val parsed = VpnConfig.parse(raw)
        assertNotNull(parsed)
        val encoded = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(advancedIni.toByteArray())
        assertEquals(parsed!!.outbound(), VpnConfig.parse(encoded)!!.outbound())
        assertEquals(parseWgProfileImport(encoded)!!.profiles.single().id, parseWgProfileImport(raw)!!.profiles.single().id)
    }

    @Test fun legacyKeepaliveAliasAndCanonicalKeyCannotOverrideEachOther() {
        val parsed = VpnConfig.parse("$legacy&keepalive=25")!!
        assertEquals(25, parsed.outbound().getAsJsonArray("peers")[0].asJsonObject["persistent_keepalive_interval"].asInt)
        assertNull(VpnConfig.parse("$legacy&keepalive=25&persistent_keepalive_interval=30"))
        assertNotNull(VpnConfig.parse("$legacy&private_key=${enc(privateKey)}"))
    }
}
