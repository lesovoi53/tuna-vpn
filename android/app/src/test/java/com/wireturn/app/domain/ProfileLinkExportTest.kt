package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsPoolSelection
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnOptions
import com.wireturn.app.data.kernel.WebdavConfig
import org.junit.Assert.*
import org.junit.Test

class ProfileLinkExportTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val source = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443" +
        "?type=tcp&security=reality&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
        "&sni=example.invalid&fp=firefox&encryption=none&support-x25519mlkem768=true#Example%20profile"

    private fun profile(kernel: KernelConfig) = Profile(id = "synthetic-profile", name = "Example profile", kernelConfig = kernel)
    private fun link(profile: Profile) = (ProfileLinkExport.original(profile) as ProfileLinkExport.Original.Link).uri
    private fun reason(profile: Profile) = (ProfileLinkExport.original(profile) as ProfileLinkExport.Original.Unavailable).reason

    @Test fun vlessImportStorageRestartAndExportPreserveOriginalProtocolLink() {
        val original = profile(KernelConfig.Vpn(requireNotNull(VpnConfig.parse(source))))
        val restored = gson.fromJson(gson.toJson(original), Profile::class.java)
        assertEquals(source, link(restored))
        assertEquals(original.kernelConfig, KernelConfig.parseUri(link(restored)))
        assertFalse(link(restored).startsWith("tuna://"))
    }

    @Test fun protocolExportDoesNotRewriteLongEncryptionOrUnrecognizedTransportHints() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184) { (it % 251).toByte() })
        val sourceWithHints = source.replace("encryption=none", "encryption=mlkem768x25519plus.native.0rtt.$key")
            .replace("#Example", "&future-transport-hint=a%2Bb%2Fc#Example")
        val original = profile(KernelConfig.Vpn(VpnConfig(uri = sourceWithHints)))
        assertEquals(sourceWithHints, link(original))
    }

    @Test fun supportedSerializersProduceReimportableProtocolLinks() {
        val kernels = listOf(
            KernelConfig.MasterDns(MasterDnsConfig(name = "Example profile", domains = listOf("example.invalid"),
                key = "synthetic-only-key", resolvers = "192.0.2.53:53")),
            KernelConfig.Qwdtt(QwdttConfig(peer = "example.invalid:443", password = "synthetic-only-password",
                vkHashes = "0123456789abcdef")),
            KernelConfig.Webdav(WebdavConfig(webdav = "https://example.invalid/storage", login = "fixture",
                password = "synthetic+password:with/slash"))
        )
        for (kernel in kernels) {
            val original = profile(kernel)
            val exported = link(original)
            assertFalse(exported.startsWith("tuna://"))
            val imported = requireNotNull(KernelConfig.parseUri(exported))
            if (kernel is KernelConfig.MasterDns) {
                val restored = (imported as KernelConfig.MasterDns).config
                assertEquals(kernel.config.domains, restored.domains)
                assertEquals(kernel.config.key, restored.key)
                assertEquals(kernel.config.resolvers, restored.resolvers)
                assertEquals(kernel.config.settings, restored.settings)
                assertEquals(exported, link(profile(imported)))
            } else assertEquals(kernel, imported)
            assertEquals(kernel, original.kernelConfig)
        }
    }

    @Test fun unsupportedAndRawConfigurationsDoNotMasqueradeAsOriginalLinks() {
        assertEquals(ProfileLinkExport.UnavailableReason.UNSUPPORTED,
            reason(profile(KernelConfig.Vpn(VpnConfig(rawXrayJson = "{}")))))
        assertEquals(ProfileLinkExport.UnavailableReason.UNSUPPORTED,
            reason(profile(KernelConfig.Vpn(VpnConfig(uri = "tuna://synthetic")))))
        assertEquals(ProfileLinkExport.UnavailableReason.UNSUPPORTED,
            reason(profile(KernelConfig.FreeTurn())))
        assertEquals(ProfileLinkExport.UnavailableReason.INVALID,
            reason(profile(KernelConfig.Vpn(VpnConfig(uri = "vless://bad uri")))))
    }

    @Test fun nativeOverridesOverlayAndLocalPoolsExplainWhyFullProfileIsNeeded() {
        val patched = profile(KernelConfig.Vpn(VpnConfig(uri = source,
            options = VpnOptions(xray = JsonObject().apply { addProperty("fixture", true) }))))
        assertEquals(ProfileLinkExport.UnavailableReason.CUSTOM_SETTINGS, reason(patched))
        assertEquals(ProfileLinkExport.UnavailableReason.CUSTOM_SETTINGS,
            reason(profile(KernelConfig.Vpn(VpnConfig(uri = source))).copy(xrayEnabled = true)))
        val pool = MasterDnsConfig(localPool = MasterDnsPoolSelection(listOf("synthetic-a", "synthetic-b")))
        assertEquals(ProfileLinkExport.UnavailableReason.LOCAL_POOL, reason(profile(KernelConfig.MasterDns(pool))))
        // Full TUNA export still preserves all settings that cannot fit a protocol URI.
        val encoded = requireNotNull(ProfileEncoder.encodeForSharing(gson.toJson(patched)))
        val restored = gson.fromJson(requireNotNull(ProfileEncoder.decode(encoded)), Profile::class.java)
        assertEquals(patched, restored)
        assertEquals(ProfileLinkExport.UnavailableReason.CUSTOM_SETTINGS, reason(restored))
    }
}
