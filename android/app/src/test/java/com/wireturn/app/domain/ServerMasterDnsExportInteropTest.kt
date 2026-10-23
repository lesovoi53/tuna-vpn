package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.SafeEnumTypeAdapterFactory
import com.wireturn.app.data.kernel.MasterDnsConfig
import java.util.Base64
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Fixtures come from the actual Lite Python exporter, with synthetic credentials only. */
class ServerMasterDnsExportInteropTest {
    private val gson = GsonBuilder().registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun cases() = JsonParser.parseString(requireNotNull(javaClass.getResourceAsStream(
        "/masterdns-server-export.json")).bufferedReader(Charsets.UTF_8).use { it.readText() })
        .asJsonObject.getAsJsonArray("cases").map { it.asJsonObject }

    private fun assertSettings(fixture: JsonObject, config: MasterDnsConfig) {
        config.validate()
        assertEquals(fixture["key"].asString, config.key)
        assertEquals(fixture["encryption"].asInt, config.encryption)
        val settings = fixture.getAsJsonObject("settings")
        assertEquals(settings["resolvers"].asString, config.resolvers)
        settings.entrySet().filter { it.key != "resolvers" }.forEach { (key, value) ->
            assertEquals(key, value.asString, config.settings[key.uppercase(Locale.ROOT)])
        }
        assertNull(config.localPool)
        assertNull(config.localPerformanceMode)
    }

    @Test fun serverTunaLinksImportOneProfileAndRoundTripThroughRealClientEncoder() {
        val fixtures = cases()
        assertEquals(12, fixtures.size)
        fixtures.forEach { fixture ->
            val link = fixture["tuna"].asString
            assertTrue(link.startsWith("tuna://"))
            assertFalse(link.substringAfter("://").contains('='))
            val decoded = requireNotNull(ProfileEncoder.decode(link.substringAfter("://")))
            val preview = previewDeepLink(link) as DeepLinkPreview.Profiles
            assertEquals(1, preview.count)
            val imported = LegacyProfileImport.parse(decoded, gson).profiles
            assertEquals(1, imported.size)
            val profile = imported.single()
            assertEquals(fixture["name"].asString, profile.name)
            assertNull(profile.subscriptionId)
            val config = (profile.kernelConfig as KernelConfig.MasterDns).config
            val expectedDomains = fixture.getAsJsonArray("domains").map { it.asString }
            assertEquals(expectedDomains, config.domains)
            assertSettings(fixture, config)
            val shared = requireNotNull(ProfileEncoder.encodeForSharing(gson.toJson(profile)))
            val restored = LegacyProfileImport.parse(requireNotNull(ProfileEncoder.decode(shared)), gson).profiles.single()
            val roundTrip = (restored.kernelConfig as KernelConfig.MasterDns).config
            assertEquals(expectedDomains, roundTrip.domains)
            assertSettings(fixture, roundTrip)
        }
    }

    @Test fun serverStormLinksEachContainExactlyOneDomainWithSameClientSettings() {
        cases().forEach { fixture ->
            val expectedDomains = fixture.getAsJsonArray("domains").map { it.asString }
            val links = fixture.getAsJsonArray("storm").map { it.asString }
            assertEquals(expectedDomains.size, links.size)
            links.forEachIndexed { index, link ->
                val config = requireNotNull(MasterDnsConfig.parse(link))
                assertEquals(listOf(expectedDomains[index]), config.domains)
                assertSettings(fixture, config)
            }
        }
    }

    @Test fun serverZlibChecksumIsVerifiedByActualClientDecoder() {
        val encoded = cases().first()["tuna"].asString.substringAfter("://")
        val bytes = Base64.getUrlDecoder().decode(encoded)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertNull(ProfileEncoder.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
    }
}
