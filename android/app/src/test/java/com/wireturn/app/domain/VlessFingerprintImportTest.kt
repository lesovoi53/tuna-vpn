package com.wireturn.app.domain

import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import org.junit.Assert.*
import org.junit.Test

class VlessFingerprintImportTest {
    private val base = "vless://00000000-0000-4000-8000-000000000001@example.com:443?security=tls&type=tcp&fp=chrome&path=%2Fkeep%2Braw&host=#My%20name"

    @Test fun excludedFingerprintsAreReplacedWithoutTouchingCredentialsOrOpaqueParameters() {
        for (fp in listOf("chrome", "random", "randomized", "%63hrome", "CHROME")) {
            val uri = base.replace("fp=chrome", "f%70=$fp")
            assertEquals(base.replace("fp=chrome", "f%70=safari"), VlessFingerprintImport.uri(uri, choose = { "safari" }))
        }
    }

    @Test fun replacementIsSelectedOnceAndRemainsStableOnReloadAndSubscriptionRefresh() {
        var choices = 0
        val imported = VlessFingerprintImport.vpn(VpnConfig(base, "xray"), choose = { choices++; "safari" })
        assertEquals(1, choices)
        assertEquals(imported, VlessFingerprintImport.vpn(imported, choose = { error("Must not randomize a saved profile") }))
        assertEquals(imported, VlessFingerprintImport.vpn(VpnConfig(base, "xray"), imported, choose = { error("Must retain the previous selection") }))
    }

    @Test fun replacingFingerprintDoesNotPreventSubscriptionCredentialUpdates() {
        val original = VlessFingerprintImport.vpn(VpnConfig(base, "xray"), choose = { "safari" })
        val kernel = KernelConfig.Vpn(original)
        val existing = Profile("local-id", "name", kernel, subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(kernel))
        val incoming = Profile("server-id", "name", KernelConfig.Vpn(VpnConfig(base.replace("example.com", "new.example.com"), "xray")))
        val normalized = VlessFingerprintImport.profile(incoming, existing)
        val merged = SubscriptionProfileMerge.kernel(existing, normalized) as KernelConfig.Vpn
        assertEquals(original.uri.replace("example.com", "new.example.com"), merged.config.uri)
        assertEquals("server-id", normalized.id)
    }

    @Test fun advancedOverridesCannotRestoreAnExcludedFingerprintAndInputIsNotMutated() {
        val patch = JsonParser.parseString("""{"streamSettings":{"tlsSettings":{"fingerprint":"random"}}}""").asJsonObject
        val input = VpnConfig(base, "xray", VpnOptions().withEngine("xray", patch))
        var choices = 0
        val output = VlessFingerprintImport.vpn(input, choose = { choices++; "firefox" })
        assertEquals(1, choices)
        assertEquals("firefox", VlessFingerprintChoices.effective(output))
        assertEquals("random", NativeOptions.get(input.options!!.forEngine("xray"), "streamSettings.tlsSettings.fingerprint")!!.asString)
    }

    @Test fun implicitChromeIsReplacedOnlyForSecureVlessAndOtherProtocolsStayUntouched() {
        val missing = base.replace("&fp=chrome", "")
        assertEquals(missing.substringBefore('#') + "&fp=firefox#My%20name", VlessFingerprintImport.uri(missing, choose = { "firefox" }))
        val plain = missing.replace("security=tls", "security=none")
        assertEquals(plain, VlessFingerprintImport.uri(plain, choose = { error("No TLS fingerprint is used") }))
        assertEquals(base.replace("vless:", "trojan:"), VlessFingerprintImport.uri(base.replace("vless:", "trojan:")))
    }

    @Test fun importedProfileNormalizesStandaloneAndChainButCanPreserveAnUnrelatedExistingChain() {
        val input = Profile("id", "name", KernelConfig.Vpn(VpnConfig(base, "xray")), vlessConfig = VlessConfig(vlessLink = base))
        val imported = VlessFingerprintImport.profile(input)
        assertFalse((imported.kernelConfig as KernelConfig.Vpn).config.uri.contains("fp=chrome"))
        assertFalse(imported.vlessConfig.vlessLink.contains("fp=chrome"))
        assertEquals(input.vlessConfig, VlessFingerprintImport.profile(input, normalizeChain = false).vlessConfig)
    }
}
