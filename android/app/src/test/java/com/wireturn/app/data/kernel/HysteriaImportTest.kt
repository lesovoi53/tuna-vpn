package com.wireturn.app.data.kernel

import org.junit.Assert.*
import org.junit.Test

class HysteriaImportTest {
    @Test fun acceptsUnescapedDisplayName() {
        assertEquals("FP test hy2", VpnConfig.parse("hy2://test-password@example.com:8443/#FP test hy2")?.displayName)
    }
    @Test fun preservesNumericHopInterval() {
        val profile = VpnConfig.parse("hy2://test-password@example.com:8443/?hop_interval=10&sni=example.com&obfs=salamander&obfs-password=test-obfs#Test")
        assertNotNull(profile)
        assertEquals("10s", profile!!.outbound()["hop_interval"].asString)
    }
    @Test fun acceptsCombinedImportThroughKernelParser() {
        val text = "hy2://test-password@example.com:8443/?hop_interval=10&sni=example.com&obfs=salamander&obfs-password=test-obfs#FP test hy2"
        val kernel = com.wireturn.app.data.KernelConfig.parseUri(text)
        assertTrue(kernel is com.wireturn.app.data.KernelConfig.Vpn)
        val profile = (kernel as com.wireturn.app.data.KernelConfig.Vpn).config
        assertEquals("FP test hy2", profile.displayName)
        val config = com.wireturn.app.kernel.SingBoxConfig.generate(profile, "127.0.0.1:2081", "test-user", "test-pass", false)
        java.io.File("build/hy2-import-config.json").apply { parentFile.mkdirs(); writeText(config.toString()) }
        assertNull(VpnConfig.parse(text.replace("hop_interval=10", "hop_interval=-1")))
        assertNull(VpnConfig.parse(text.replace("hop_interval=10", "hop_interval=0")))
    }
}
