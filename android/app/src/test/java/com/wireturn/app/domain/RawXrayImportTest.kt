package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.SafeEnumTypeAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class RawXrayImportTest {
    private val gson = GsonBuilder().registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun config(name: String, balancer: Boolean = false, port: Int = 443): String = """{
      "remarks":"$name","log":{"loglevel":"warning"},
      "dns":{"servers":["1.1.1.1"]},
      "inbounds":[{"tag":"client","listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"udp":true}}],
      "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"fixture.invalid","port":$port,"users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"fixture.invalid"}}},
      {"tag":"direct","protocol":"freedom"}],
      "routing":{"domainStrategy":"AsIs",${if (balancer) "\"balancers\":[{\"tag\":\"pool\",\"selector\":[\"proxy\"],\"strategy\":{\"type\":\"random\"}}]," else ""}
      "rules":[{"type":"field","network":"tcp,udp",${if (balancer) "\"balancerTag\":\"pool\"" else "\"outboundTag\":\"proxy\""}}]}}
    """.trimIndent()

    @Test fun fullXrayObjectBecomesOneProfile() {
        val result = LegacyProfileImport.parse(config("Endpoint"), gson)
        assertEquals(1, result.profiles.size)
        assertEquals(0, result.skippedEntries)
        assertEquals("Endpoint", result.profiles.single().name)
        assertTrue(result.profiles.single().kernelConfig is KernelConfig.Vpn)
    }

    @Test fun fullXrayArrayRetainsEndpointAndBalancer() {
        val result = LegacyProfileImport.parse("[${config("Endpoint")},${config("Pool", true)}]", gson)
        assertEquals(listOf("Endpoint", "Pool"), result.profiles.map { it.name })
        assertEquals(0, result.skippedEntries)
        val serialized = gson.toJson(result.profiles.last())
        assertTrue(serialized.contains("balancers"))
        assertTrue(serialized.contains("routing"))
        assertTrue(serialized.contains("dns"))
    }

    @Test fun namedSourceIdentitySurvivesReorderingAndEndpointUpdate() {
        val before = LegacyProfileImport.parse("[${config("Endpoint")},${config("Pool", true)}]", gson)
        val after = LegacyProfileImport.parse("[${config("Pool", true, 8443)},${config("Endpoint", port = 8443)}]", gson)
        assertEquals(2, before.profiles.size)
        assertEquals(before.profiles.associate { it.name to it.id }, after.profiles.associate { it.name to it.id })
    }

    @Test fun sameNameDoesNotCoalesceDifferentDocuments() {
        val result = LegacyProfileImport.parse("[${config("Same")},${config("Same", port = 8443)}]", gson)
        assertEquals(2, result.profiles.size)
        assertEquals(2, result.profiles.map { it.id }.distinct().size)
    }

    @Test fun malformedNeighborDoesNotDiscardValidConfig() {
        val result = LegacyProfileImport.parse("[${config("Good")},{\"outbounds\":[],\"routing\":{}}]", gson)
        assertEquals(listOf("Good"), result.profiles.map { it.name })
        assertEquals(1, result.skippedEntries)
    }

    @Test fun exportedProfileRoundtripRetainsWholeNativeDocument() {
        val imported = LegacyProfileImport.parse(config("Pool", true), gson).profiles.single()
        val reimported = LegacyProfileImport.parse(gson.toJson(imported), gson).profiles.single()
        val before = (imported.kernelConfig as KernelConfig.Vpn).config
        val after = (reimported.kernelConfig as KernelConfig.Vpn).config
        assertEquals(before.rawXrayJson, after.rawXrayJson)
        assertTrue(after.isXrayBalancer)
        assertEquals(imported.id, reimported.id)
    }

    @Test fun duplicateFieldsInOriginalRawContainerAreRejected() {
        val duplicated = config("Pool", true).replace("\"remarks\":\"Pool\"", "\"remarks\":\"Pool\",\"remarks\":\"Other\"")
        assertThrows(IllegalArgumentException::class.java) { LegacyProfileImport.parse("[$duplicated]", gson) }
    }

    @Test fun subscriptionRefreshPreservesLocalDnsAndUpdatesRemoteEndpoint() {
        val imported = LegacyProfileImport.parse(config("Pool", true), gson).profiles.single()
        val baseConfig = (imported.kernelConfig as KernelConfig.Vpn).config
        val local = imported.copy(subscriptionKernelBaseline = SubscriptionProfileMerge.snapshot(imported.kernelConfig),
            kernelConfig = KernelConfig.Vpn(baseConfig.copy(rawXrayJson = baseConfig.rawXrayJson!!.replace("1.1.1.1", "9.9.9.9"))))
        val incoming = LegacyProfileImport.parse(config("Pool", true, 8443), gson).profiles.single()
        val merged = (SubscriptionProfileMerge.kernel(local, incoming) as KernelConfig.Vpn).config
        val root = com.google.gson.JsonParser.parseString(merged.rawXrayJson).asJsonObject
        assertEquals("9.9.9.9", root.getAsJsonObject("dns").getAsJsonArray("servers")[0].asString)
        assertEquals(8443, root.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("settings")
            .getAsJsonArray("vnext")[0].asJsonObject["port"].asInt)
        assertTrue(merged.isXrayBalancer)
    }
}
