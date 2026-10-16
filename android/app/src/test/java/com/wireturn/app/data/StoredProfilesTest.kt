package com.wireturn.app.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.data.kernel.VpnOptions
import org.junit.Assert.*
import org.junit.Test

class StoredProfilesTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun profile(advanced: String) = Profile("fixture", "Настройки 🐟", KernelConfig.Vpn(VpnConfig(
        "vless://11111111-2222-4333-8444-555555555555@192.0.2.1:443?security=tls&type=xhttp",
        "xray", VpnOptions(xray = JsonParser.parseString(advanced).asJsonObject))))
    private fun restored(advanced: String): String {
        val loaded = decodeStoredProfiles(gson.toJson(listOf(profile(advanced))), gson, "Profile").single()
        assertEquals("Настройки 🐟", loaded.name)
        return (loaded.kernelConfig as KernelConfig.Vpn).config.options!!.xray.toString()
    }

    @Test fun integerNativeOptionsStayIntegerTokensAfterPreferenceRead() {
        val advanced = """{"mux":{"enabled":true,"concurrency":8},"streamSettings":{"sockopt":{"tcpUserTimeout":10000}}}"""
        assertEquals(advanced, restored(advanced))
    }

    @Test fun arbitraryJsonNumbersNeverRoundThroughDouble() {
        val advanced = """{"largeInteger":9223372036854775807,"decimal":0.12345678901234567890123456789,"exponent":1e3}"""
        assertEquals(advanced, restored(advanced))
    }

    @Test fun independentInvalidRecordDoesNotDiscardValidNeighbours() {
        val first = profile("{}").copy(id = "first")
        val last = profile("{}").copy(id = "last")
        val json = "[" + gson.toJson(first) + ",null,17," + gson.toJson(last) + "]"
        assertEquals(listOf("first", "last"), decodeStoredProfiles(json, gson, "Profile").map { it.id })
    }

    @Test fun emitsTheExecutableNativeConfigAfterReload() {
        val original = profile("""{"mux":{"enabled":true,"concurrency":8}}""")
        val originalConfig = (original.kernelConfig as KernelConfig.Vpn).config.let { it.copy(uri = it.uri.replace("type=xhttp", "type=tcp")) }
        com.wireturn.app.kernel.XrayVpnConfig.generate(originalConfig, "127.0.0.1:21880", "qa", "qa", false)
        val loaded = decodeStoredProfiles(gson.toJson(listOf(original)), gson, "Profile").single()
        val config = (loaded.kernelConfig as KernelConfig.Vpn).config.let { it.copy(uri = it.uri.replace("type=xhttp", "type=tcp")) }
        val native = com.wireturn.app.kernel.XrayVpnConfig.generate(config, "127.0.0.1:21880", "qa", "qa", false)
        java.io.File("build/release-fixtures").mkdirs()
        java.io.File("build/release-fixtures/stored-profile-numbers.json").writeText(native.toString())
    }

    @Test fun repairsKnownIntegerFieldsWrittenAsDoublesByOldVersions() {
        val advanced = """{"mux":{"concurrency":8.0},"streamSettings":{"sockopt":{"tcpUserTimeout":1e4}},"unknown":0.125}"""
        assertEquals("""{"mux":{"concurrency":8},"streamSettings":{"sockopt":{"tcpUserTimeout":10000}},"unknown":0.125}""", restored(advanced))
    }

    @Test fun doesNotCoerceFractionalOrOutOfRangeOrStringSettings() {
        for (value in listOf("8.25", "9223372036854775808.0", "\"8.0\"")) {
            val advanced = """{"mux":{"concurrency":$value}}"""
            assertEquals(advanced, restored(advanced))
        }
    }

    @Test fun nullOldStoreHasNoProfiles() {
        assertTrue(decodeStoredProfiles("null", gson, "Profile").isEmpty())
    }

    @Test fun repairsSingBoxIntegerFieldsWithoutMovingOptionsBetweenCores() {
        val base = profile("{}")
        val config = (base.kernelConfig as KernelConfig.Vpn).config
        val source = base.copy(kernelConfig = KernelConfig.Vpn(config.copy(engine = "sing-box",
            options = VpnOptions(singBox = JsonParser.parseString("""{"multiplex":{"max_streams":16.0},"unknown":0.125}""").asJsonObject))))
        val loaded = decodeStoredProfiles(gson.toJson(listOf(source)), gson, "Profile").single()
        val options = (loaded.kernelConfig as KernelConfig.Vpn).config.options!!
        assertNull(options.xray)
        assertEquals("""{"multiplex":{"max_streams":16},"unknown":0.125}""", options.singBox.toString())
    }

    @Test fun archiveExportedByOldVersionCanBeImportedAgain() {
        val source = profile("""{"mux":{"enabled":true,"concurrency":8.0}}""")
        val config = (source.kernelConfig as KernelConfig.Vpn).config
        val archive = gson.toJson(listOf(source.copy(kernelConfig = KernelConfig.Vpn(config.copy(uri = config.uri.replace("type=xhttp", "type=tcp"))))))
        val parsed = com.wireturn.app.domain.LegacyProfileImport.parse(archive, gson)
        assertEquals(0, parsed.skippedEntries)
        assertEquals(1, parsed.profiles.size)
        val reloaded = (parsed.profiles.single().kernelConfig as KernelConfig.Vpn).config
        com.wireturn.app.kernel.XrayVpnConfig.generate(reloaded, "127.0.0.1:21880", "qa", "qa", false)
    }
}
