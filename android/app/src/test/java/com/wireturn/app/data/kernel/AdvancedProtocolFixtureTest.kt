package com.wireturn.app.data.kernel

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import com.wireturn.app.kernel.SingBoxConfig

/** Opt-in private fixture audit; input and generated secrets stay under ignored build/. */
class AdvancedProtocolFixtureTest {
    @Test fun privateAdvancedFixtures() {
        val directory = System.getenv("TUNA_ADVANCED_FIXTURES")?.let(::File)
        assumeTrue(directory?.isDirectory == true)
        val rows = mutableListOf<Map<String,Any>>()
        directory!!.listFiles { f -> f.extension == "uri" }!!.sortedBy { it.name }.forEach { file ->
            val text = file.readText().trim()
            val links = if (text.startsWith("vpn://")) AwgUri.normalizeAll(text) else listOf(text)
            links.forEachIndexed { index, uri ->
                val p = VpnConfig.parse(uri)
                assertNotNull(file.name, p)
                val config = SingBoxConfig.generate(p!!,"127.0.0.1:29080",allowNoAuth=true)
                val name = file.nameWithoutExtension + "-$index"
                File(directory,"$name.json").writeText(config.toString())
                rows += mapOf("fixture" to file.name, "index" to index, "protocol" to p.protocol, "valid" to p.isValid())
            }
        }
        File(directory,"summary.json").writeText(com.google.gson.Gson().toJson(rows))
        assertTrue(rows.isNotEmpty())
    }
}
