package com.wireturn.app.data.kernel

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import com.wireturn.app.kernel.SingBoxConfig

/** Always audit synthetic fixtures; optionally add private inputs without exposing their secrets. */
class AdvancedProtocolFixtureTest {
    @Test fun bundledAndOptionalPrivateFixtures() {
        val synthetic = linkedMapOf(
            "snell-v6" to "snell://fixture-only@vpn.invalid:443?version=6&udp-relay=true&mode=default",
            "tuic" to "tuic://00000000-0000-4000-8000-000000000001:fixture-only@vpn.invalid:443?congestion_control=bbr",
            "anytls" to "anytls://fixture-only@vpn.invalid:443?min_idle_session=5&idle_session_timeout=30s"
        )
        synthetic["awg-extended"] = checkNotNull(javaClass.getResource("/awg/extended-panel-synthetic.conf")) {
            "Missing bundled synthetic AWG fixture"
        }.readText()
        val output = File("build/advanced-synthetic-fixtures")
        check(output.isDirectory || output.mkdirs()) { "Cannot create synthetic fixture output directory" }
        audit(synthetic, output)

        val configured = System.getenv("TUNA_ADVANCED_FIXTURES") ?: return
        require(configured.isNotBlank()) { "TUNA_ADVANCED_FIXTURES must name a non-empty fixture directory" }
        val directory = File(configured)
        require(directory.isDirectory) { "TUNA_ADVANCED_FIXTURES must name an existing directory" }
        val files = checkNotNull(directory.listFiles { f -> f.isFile && f.extension == "uri" }) {
            "Cannot list TUNA_ADVANCED_FIXTURES"
        }.sortedBy { it.name }
        require(files.isNotEmpty()) { "TUNA_ADVANCED_FIXTURES contains no .uri files" }
        audit(files.associate { it.nameWithoutExtension to it.readText().trim() }, directory)
    }

    private fun audit(fixtures: Map<String, String>, directory: File) {
        val rows = mutableListOf<Map<String, Any>>()
        fixtures.forEach { (name, text) ->
            val links = if (text.startsWith("vpn://")) AwgUri.normalizeAll(text) else listOf(text)
            assertTrue("$name produced no profiles", links.isNotEmpty())
            links.forEachIndexed { index, uri ->
                val profile = VpnConfig.parse(uri)
                assertNotNull("$name cannot be parsed", profile)
                assertTrue("$name is invalid", profile!!.isValid())
                val config = SingBoxConfig.generate(profile, "127.0.0.1:29080", allowNoAuth = true)
                val entriesKey = if (profile.protocol in setOf("awg", "wireguard")) "endpoints" else "outbounds"
                assertTrue("$name has no $entriesKey", config.has(entriesKey))
                assertEquals("$name must generate one VPN entry", 1, config.getAsJsonArray(entriesKey).size())
                assertEquals("vpn", config.getAsJsonArray(entriesKey)[0].asJsonObject.get("tag").asString)
                assertEquals("vpn", config.getAsJsonObject("route").get("final").asString)
                File(directory, "$name-$index.json").writeText(config.toString())
                rows += mapOf("fixture" to name, "index" to index, "protocol" to profile.protocol, "valid" to true)
            }
        }
        assertTrue(rows.isNotEmpty())
        File(directory, "summary.json").writeText(com.google.gson.Gson().toJson(rows))
    }
}
