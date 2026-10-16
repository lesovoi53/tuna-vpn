package com.wireturn.app.data

import org.junit.Assert.*
import org.junit.Test

class DnsPresetsTest {
    @Test fun retiredXboxIsNotOfferedAndCustomSettingsArePreserved() {
        assertFalse(DnsPresets.all.any { it.id == "xbox" })
        assertFalse(DnsPresets.endpoints.any { it.id.startsWith("xbox-") })
        val oldCustom = "111.88.96.50,111.88.96.51"
        assertEquals(oldCustom, XraySettings(dns = oldCustom).fillDefaults().dns)
        assertNull(DnsPresets.matching(oldCustom))
    }
    @Test fun customServersAreNormalizedWithoutChangingDohPaths() {
        val raw = " 9.9.9.9\r\nhttps://dns.example/dns-query?key=test\n9.9.9.9, 149.112.112.112 "
        assertEquals("9.9.9.9,https://dns.example/dns-query?key=test,149.112.112.112", XraySettings(dns = raw).fillDefaults().dns)
        assertNull(DnsPresets.matching(raw))
        assertEquals("yandex", DnsPresets.matching("77.88.8.8\n77.88.8.1")!!.id)
        assertTrue(DnsPresets.all.filter { it.id in listOf("yandex", "skydns") }.none { it.servers.contains("https:") })
    }
}
