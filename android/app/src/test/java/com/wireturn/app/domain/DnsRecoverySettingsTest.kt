package com.wireturn.app.domain

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DnsRecoverySettingsTest {
    private fun tree(settings: SplitDnsSettings) = JsonParser.parseString(settings.toJson()).asJsonObject
    private fun source(dns: String): String {
        val document = """{"version":1,"policy":{"RemoteDns":"192.0.2.53","DomesticDns":"192.0.2.54"},"dns":$dns}"""
        return TunaRoutingLink.parse("tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(document.toByteArray())).source
    }

    @Test fun explicitRecoveryAndBootstrapSurviveStoredSourceAndNativePolicy() {
        val dns = """{
          "remoteEmergency":{"mode":"system"},
          "domesticEmergency":{"mode":"resolver","resolver":{"transport":"DOH","endpoint":"https://private.example/dns-query","route":"DIRECT"}},
          "bootstrap":{"mode":"system"}
        }"""
        val stored = source(dns)
        val settings = SplitDnsSettings.fromPolicy(SubscriptionPolicyParser.parseHapp(stored))
        val reparsed = tree(SplitDnsSettings.parse(settings.toJson()))
        assertEquals("system", reparsed.getAsJsonObject("remoteEmergency")["mode"].asString)
        assertEquals("https://private.example/dns-query", reparsed.getAsJsonObject("domesticEmergency")
            .getAsJsonObject("resolver")["endpoint"].asString)
        val native = JsonParser.parseString(SubscriptionRoutingAssets.nativePolicy(stored)).asJsonObject
            .getAsJsonObject("splitDns")
        for (field in listOf("remoteEmergency", "domesticEmergency", "bootstrap")) {
            assertEquals(field, reparsed[field], native[field])
        }
    }

    @Test fun subscriptionDefaultsToSystemButCommonUnspecifiedSettingsRemainStrict() {
        val settings = SplitDnsSettings.fromPolicy(SubscriptionPolicyParser.parseHapp(source("{}")))
        for (field in listOf("remoteEmergency", "domesticEmergency")) {
            assertEquals(field, "system", tree(settings).getAsJsonObject(field)?.get("mode")?.asString)
            assertFalse("Common settings must not silently acquire a direct escape", tree(SplitDnsSettings.parse("{}")).has(field))
        }
        assertFalse("Omitted bootstrap inherits the user's global setting", tree(settings).has("bootstrap"))
    }

    @Test fun disabledNeverTurnsIntoDefaultAndExplicitOverrideReplacesOldReserve() {
        val settings = SplitDnsSettings.fromPolicy(SubscriptionPolicyParser.parseHapp(source("""{
          "remoteEmergency":{"mode":"disabled"},"domesticEmergency":{"mode":"disabled"}
        }""")))
        assertEquals("disabled", tree(settings).getAsJsonObject("remoteEmergency")["mode"].asString)
        val base = """{"splitDns":{"remoteEmergency":{"mode":"system"},"bootstrap":{"mode":"system"}}}"""
        val override = SplitDnsSettings.applyToPolicy(base, settings.toJson())
        val dns = JsonParser.parseString(override).asJsonObject.getAsJsonObject("splitDns")
        assertEquals("disabled", dns.getAsJsonObject("remoteEmergency")["mode"].asString)
        assertFalse("Absent override bootstrap means inherit global, not stale source", dns.has("bootstrap"))
    }

    @Test fun recoveryIsSeparateFromEightPrimaryMembers() {
        val servers = (1..8).joinToString(",") { """{"transport":"UDP","endpoint":"192.0.2.$it","route":"VPN"}""" }
        val settings = SplitDnsSettings.parse("""{"remoteServers":[$servers],
          "remoteEmergency":{"mode":"resolver","resolver":{"transport":"TCP","endpoint":"192.0.2.99:5353","route":"VPN"}}}""")
        assertEquals(8, settings.effectiveRemoteServers.size)
        assertEquals("192.0.2.99:5353", tree(settings).getAsJsonObject("remoteEmergency")
            .getAsJsonObject("resolver")["endpoint"].asString)
    }

    @Test fun bootstrapAllowsSystemOrLiteralTcpOnly() {
        for (endpoint in listOf("192.0.2.53:5353", "[2001:db8::53]:53")) {
            val settings = SplitDnsSettings.parse("""{"bootstrap":{"mode":"resolver","resolver":{"transport":"TCP","endpoint":"$endpoint","route":"DIRECT"}}}""")
            assertEquals(endpoint, tree(settings).getAsJsonObject("bootstrap").getAsJsonObject("resolver")["endpoint"].asString)
        }
    }

    @Test fun malformedOrWrongRouteRecoveryFailsClosed() {
        val invalid = listOf(
            """{"remoteEmergency":null}""",
            """{"remoteEmergency":{"mode":"unknown"}}""",
            """{"remoteEmergency":{"mode":"system","extra":true}}""",
            """{"remoteEmergency":{"mode":"system","resolver":{"transport":"UDP","endpoint":"192.0.2.1"}}}""",
            """{"remoteEmergency":{"mode":"resolver"}}""",
            """{"remoteEmergency":{"mode":"resolver","resolver":{"transport":"TCP","endpoint":"192.0.2.1","route":"DIRECT"}}}""",
            """{"domesticEmergency":{"mode":"resolver","resolver":{"transport":"TCP","endpoint":"192.0.2.1","route":"VPN"}}}""",
            """{"bootstrap":{"mode":"disabled"}}""",
            """{"bootstrap":{"mode":"resolver","resolver":{"transport":"UDP","endpoint":"192.0.2.1","route":"DIRECT"}}}""",
            """{"bootstrap":{"mode":"resolver","resolver":{"transport":"TCP","endpoint":"resolver.example:53","route":"DIRECT"}}}""",
            """{"bootstrap":{"mode":"resolver","resolver":{"transport":"TCP","endpoint":"192.0.2.1","bootstrapIp":"192.0.2.2","route":"DIRECT"}}}"""
        )
        invalid.forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { SplitDnsSettings.parse(value) }
        }
    }
}
