package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MasterDnsPerformanceTest {
    private val profile = MasterDnsConfig(
        domains = listOf("dns.example.invalid"), key = "synthetic-key", resolvers = "192.0.2.53",
        settings = mapOf(
            "RESOLVER_BALANCING_STRATEGY" to "4",
            "PACKET_DUPLICATION_COUNT" to "5",
            "SETUP_PACKET_DUPLICATION_COUNT" to "6",
            "MIN_UPLOAD_MTU" to "40", "ARQ_WINDOW_SIZE" to "700"
        )
    )
    private fun toml(config: MasterDnsConfig) = config.toml("127.0.0.1:2081", "fixture", "fixture", true)

    @Test fun presetsChangeOnlyThreeRequestedNativeValuesAndRestoreSourceExactly() {
        val expected = mapOf(
            MasterDnsPerformanceMode.SPEED to listOf("6", "1", "2"),
            MasterDnsPerformanceMode.BALANCED to listOf("6", "2", "3"),
            MasterDnsPerformanceMode.STABLE to listOf("8", "3", "4")
        )
        val keys = listOf("RESOLVER_BALANCING_STRATEGY", "PACKET_DUPLICATION_COUNT", "SETUP_PACKET_DUPLICATION_COUNT")
        expected.forEach { (mode, values) ->
            val selected = profile.copy(localPerformanceMode = mode.wireValue)
            assertEquals(profile.settings, selected.settings)
            assertEquals(profile.key, selected.key)
            assertEquals(profile.encryption, selected.encryption)
            MasterDnsFields.all.forEach { field ->
                val expectedValue = keys.indexOf(field.key).takeIf { it >= 0 }?.let(values::get) ?: profile.value(field)
                assertEquals(field.key, expectedValue, selected.effectiveValue(field))
            }
            val native = toml(selected)
            keys.zip(values).forEach { (key, value) -> assertTrue(native.lines().contains("$key = $value")) }
            assertEquals(toml(profile), toml(selected.copy(localPerformanceMode = null)))
        }
    }

    @Test fun everyModeSurvivesStorageAndUriWithoutReplacingPortableValues() {
        MasterDnsPerformanceMode.entries.forEach { mode ->
            val selected = profile.copy(localPerformanceMode = mode.wireValue)
            val stored = Gson().fromJson(Gson().toJson(selected), MasterDnsConfig::class.java)
            val imported = requireNotNull(MasterDnsConfig.parse(stored.toUri()))
            assertEquals(mode, imported.performanceMode())
            assertEquals(profile.settings, imported.settings)
            assertEquals(toml(selected), toml(imported))
            val wireProfile = JsonParser.parseString(String(Base64.getDecoder().decode(
                imported.toUri().substringAfter("://")), Charsets.UTF_8)).asJsonObject.getAsJsonObject("profile")
            assertEquals(mode.wireValue, wireProfile["tuna_performance_mode"].asString)
            assertEquals(5, wireProfile["packet_duplication_count"].asInt)
            assertEquals(6, wireProfile["setup_packet_duplication_count"].asInt)
            val cleared = requireNotNull(MasterDnsConfig.parse(imported.copy(localPerformanceMode = null).toUri()))
            assertNull(cleared.localPerformanceMode)
            assertEquals(toml(profile), toml(cleared))
        }
    }

    @Test fun oldSnapshotsAndLinksKeepOriginalPerformance() {
        val old = Gson().toJsonTree(profile).asJsonObject.apply { remove("localPerformanceMode") }
        val stored = Gson().fromJson(old, MasterDnsConfig::class.java)
        val imported = requireNotNull(MasterDnsConfig.parse(profile.toUri()))
        assertNull(stored.localPerformanceMode)
        assertNull(imported.localPerformanceMode)
        assertEquals(toml(profile), toml(stored))
        assertEquals(toml(profile), toml(imported))
    }

    @Test fun invalidEnumsAndTypesCannotSilentlySelectAnotherMode() {
        val badValues: List<JsonElement> = listOf(
            JsonPrimitive("fast"), JsonPrimitive("SPEED"), JsonPrimitive("profile"),
            JsonPrimitive(""), JsonPrimitive(1), JsonPrimitive(true), JsonNull.INSTANCE,
            JsonParser.parseString("""{"mode":"speed"}""")
        )
        badValues.forEach { value ->
            val root = JsonParser.parseString(String(Base64.getDecoder().decode(
                profile.toUri().substringAfter("://")), Charsets.UTF_8)).asJsonObject
            root.getAsJsonObject("profile").add("tuna_performance_mode", value)
            val link = "stormdns://" + Base64.getEncoder().encodeToString(root.toString().toByteArray(Charsets.UTF_8))
            assertNull("Rejected extension $value", MasterDnsConfig.parse(link))
        }
        assertFalse(profile.copy(localPerformanceMode = "unknown").isValid())
        assertThrows(IllegalArgumentException::class.java) { toml(profile.copy(localPerformanceMode = "unknown")) }
    }
}
