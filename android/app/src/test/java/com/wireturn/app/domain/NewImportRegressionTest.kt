package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class NewImportRegressionTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val trust = "tt://synthetic:synthetic-pass@example.invalid:443?congestion_control=bbr&client_random=1234abcd/ffffffff&security=tls&sni=edge.example.invalid#Test"
    private val vless = "vless://11111111-2222-4333-8444-555555555555@example.invalid:443?encryption=none&type=xhttp&path=%2Fexample&mode=auto&extra=%7B%22xmux%22%3A%7B%22cMaxReuseTimes%22%3A%2264-128%22%2C%22maxConcurrency%22%3A%2216-32%22%2C%22maxConnections%22%3A0%2C%22hKeepAlivePeriod%22%3A30%2C%22hMaxRequestTimes%22%3A%22600-900%22%2C%22hMaxReusableSecs%22%3A%221800-3000%22%7D%2C%22xPaddingBytes%22%3A%22100-1000%22%7D&security=tls&sni=example.invalid&fp=firefox&alpn=h2#Test"

    @Test fun readableTrustTunnelAliasImportsThroughFileBoundary() {
        val result = ProfileFileImport.parse(trust, gson)
        assertEquals(0, result.skippedEntries)
        val config = (result.profiles.single().kernelConfig as KernelConfig.Vpn).config
        assertEquals("1234abcd/ffffffff", config.outbound()["client_random_prefix"].asString)
        assertEquals("bbr", config.outbound()["congestion_controller"].asString)
    }

    @Test fun markdownEscapedTrustTunnelImportsThroughFileBoundary() {
        val escaped = trust.replace("@", "\\@").replace("_", "\\_")
        assertEquals(1, ProfileFileImport.parse(escaped, gson).profiles.size)
    }

    @Test fun markdownEscapedVlessImportsWithoutChangingIdOrXhttpOptions() {
        val escaped = vless.replace("://", "\\://").replace("@", "\\@")
        val result = ProfileFileImport.parse(escaped, gson)
        assertEquals(0, result.skippedEntries)
        assertEquals(VpnConfig.parse(vless)?.uri, (result.profiles.single().kernelConfig as KernelConfig.Vpn).config.uri)
    }

    @Test fun malformedLongVlessIdMustNotBeGuessedOrSilentlyRewritten() {
        assertNotNull(VpnConfig.parse(vless))
        assertNull(VpnConfig.parse(vless.replace("8444", "8444n")))
    }

    @Test fun trustRandomAliasesMustAgreeAndRespectCronetRestrictions() {
        assertNull(VpnConfig.parse(trust.substringBefore('#') + "&client_random_prefix=abcd"))
        assertNull(VpnConfig.parse(trust.substringBefore('#') + "&use_cronet_https=true"))
        assertNotNull(VpnConfig.parse(trust.substringBefore('#') + "&client_random_prefix=1234abcd/ffffffff"))
        for (prefix in listOf("abc/fff", "abcd/ff", "gg/ff", "ab".repeat(33))) {
            assertNull(VpnConfig.parse(trust.replace("1234abcd/ffffffff", prefix)))
        }
    }

    @Test fun encodedCredentialBackslashAndQueryValuesAreNeverUnescaped() {
        val config = VpnConfig.parse(trust.replace("synthetic-pass", "pass%5C_name"))!!
        assertEquals("pass\\_name", config.outbound()["password"].asString)
        assertNull(VpnConfig.parse(trust.replace("synthetic-pass", "pass\\qname")))
        val source = vless.replace("%2Fexample", "%2Fexample%5C_part")
        assertEquals(source, com.wireturn.app.data.kernel.VpnImportText.normalize(source))
    }

    @Test fun editingTrustTunnelKeepsTheAliasedPrefixAndMask() {
        val config = VpnConfig.parse(trust)!!
        val edited = com.wireturn.app.data.kernel.VpnEditorDraft.from(config.uri).change("port", "8443").toUri()
        assertEquals("1234abcd/ffffffff", VpnConfig.parse(edited)!!.outbound()["client_random_prefix"].asString)
    }
}
