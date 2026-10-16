package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.R
import com.wireturn.app.data.kernel.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionFactsTest {
    @Test fun snellReuseDetailsUseTheEffectiveSettingAndLocalizedValues() {
        val base = VpnConfig("snell://fixture@192.0.2.1:443?version=5&reuse=true", "sing-box")
        val enabled = ConnectionFacts.from(KernelConfig.Vpn(base)).rows.single { it.label == R.string.cd_reuse }
        assertEquals(R.string.cd_enabled, enabled.valueResource)
        val patch = com.google.gson.JsonObject().apply { addProperty("reuse", false) }
        val disabled = ConnectionFacts.from(KernelConfig.Vpn(base.copy(options = VpnOptions(singBox = patch))))
            .rows.single { it.label == R.string.cd_reuse }
        assertEquals(R.string.cd_disabled, disabled.valueResource)
        assertFalse(disabled.sensitive)
    }

    @Test fun snellDetailsShowEffectiveTfoInsteadOfTheImportedRequestedValue() {
        val c = VpnConfig("snell://fixture@192.0.2.1:443?version=5&tfo=true", "sing-box")
        val row = ConnectionFacts.from(KernelConfig.Vpn(c)).rows.single { it.label == R.string.cd_tfo }
        assertEquals(R.string.snell_tfo_disabled_compatibility, row.valueResource)
        val allowed = c.copy(options = VpnOptions(allowExperimentalSnellTfo = true))
        assertEquals(R.string.cd_enabled, ConnectionFacts.from(KernelConfig.Vpn(allowed)).rows.single { it.label == R.string.cd_tfo }.valueResource)
    }
    @Test fun compatibilityPermissionAloneDoesNotClaimNativeOverridesWereConfigured() {
        val c = VpnConfig("snell://fixture@192.0.2.1:443?version=5&tfo=true", "sing-box", VpnOptions())
        assertFalse(ConnectionFacts.from(KernelConfig.Vpn(c)).rows.any { it.label == R.string.cd_extra })
        val patch = com.google.gson.JsonObject().apply { addProperty("reuse", false) }
        assertTrue(ConnectionFacts.from(KernelConfig.Vpn(c.copy(options = VpnOptions(singBox = patch))))
            .rows.any { it.label == R.string.cd_extra })
    }
    @Test fun neverDisplaysVpnCredentialsOrArbitraryOptions() {
        val c = KernelConfig.Vpn(VpnConfig("vless://secret@vpn.example:443?type=xhttp&security=reality&sni=cover.example&pbk=private-key&sid=secret-id&extra=secret-json&fp=firefox"))
        val facts = ConnectionFacts.from(c)
        assertEquals("vpn.example:443", facts.server)
        assertTrue(facts.rows.any { it.label == R.string.cd_transport && it.value == "xhttp" })
        assertFalse(facts.toString().contains("secret")); assertFalse(facts.toString().contains("private-key"))
    }

    @Test fun privacyKeepsProtocolSettingsButMasksNetworkAddresses() {
        val facts = ConnectionFacts.from(KernelConfig.Vpn(VpnConfig(
            "vless://secret@vpn.example:443?type=xhttp&security=reality&sni=cover.example&fp=firefox&port=8443")))
        val hidden = facts.rows.filter { it.masked(true) }.map { it.label }.toSet()
        assertEquals(setOf(R.string.cd_sni, R.string.cd_ports), hidden)
        assertTrue(facts.rows.filterNot { it.masked(true) }.any { it.value == "xhttp" })
        assertTrue(facts.rows.filterNot { it.masked(true) }.any { it.value == "firefox" })
        assertTrue(facts.rows.none { it.masked(false) })
    }
    @Test fun storageAndTurnNeverIncludePasswords() {
        for (c in listOf(KernelConfig.Webdav(WebdavConfig(webdav="https://store.example:8443/path", password="secret")),
            KernelConfig.Qwdtt(QwdttConfig(peer="198.51.100.1:56000", password="secret")))) {
            assertFalse(ConnectionFacts.from(c).toString().contains("secret"))
        }
        assertEquals("store.example:8443", ConnectionFacts.endpoint("https://user:secret@store.example:8443/path?token=secret"))
    }
    @Test fun allVpnProtocolLabelsAreAvailableWithoutNetworkRequests() {
        for (scheme in listOf("snell", "vless", "hy2", "mierus", "trojan", "ss", "tuic", "anytls", "naive+https")) {
            val facts = ConnectionFacts.from(KernelConfig.Vpn(VpnConfig("$scheme://user:secret@vpn.example:443")))
            assertTrue(facts.protocol.isNotBlank()); assertFalse(facts.toString().contains("secret"))
        }
    }
}
