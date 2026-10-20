package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MasterDnsPoolSelectionTest {
    private val config = MasterDnsConfig(domains = listOf("dns.example.invalid"),
        key = "synthetic-key", resolvers = "192.0.2.53")

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid pool to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test fun supportsOnlyTwoToEightUniqueNonBlankIdsAndKnownStrategies() {
        for (count in listOf(2, 8)) {
            for (strategy in listOf("round_robin", "least_connections")) {
                MasterDnsPoolSelection((1..count).map { "id-$it" }, strategy).validate()
            }
        }
        for (ids in listOf(emptyList(), listOf("a"), (1..9).map { "id-$it" },
            listOf("a", "a"), listOf("a", " "), listOf("", "a"))) {
            reject { MasterDnsPoolSelection(ids).validate() }
        }
        reject { MasterDnsPoolSelection(listOf("a", "b"), "random").validate() }
        reject { Gson().fromJson("""{"memberIds":null}""", MasterDnsPoolSelection::class.java).validate() }
        reject { Gson().fromJson("""{"memberIds":["a",null],"strategy":"round_robin"}""",
            MasterDnsPoolSelection::class.java).validate() }
    }

    @Test fun oldGsonConfigsHaveNoPoolAndLocalSnapshotKeepsOnlyMembership() {
        assertNull(Gson().fromJson(Gson().toJson(config), MasterDnsConfig::class.java).localPool)
        val pooled = config.copy(localPool = MasterDnsPoolSelection(listOf("a", "b")))
        val restored = Gson().fromJson(Gson().toJson(pooled), MasterDnsConfig::class.java)
        assertEquals(pooled, restored)
        val selectionJson = Gson().toJsonTree(restored.localPool).asJsonObject
        assertEquals(setOf("memberIds", "strategy"), selectionJson.keySet())
    }

    @Test fun activePoolCannotExportAsSingleServerUriButPlainChildStillWorks() {
        val pooled = config.copy(localPool = MasterDnsPoolSelection(listOf("a", "b")))
        pooled.validate()
        reject { pooled.toUri() }
        val child = pooled.copy(localPool = null)
        assertEquals(config.domains, MasterDnsConfig.parse(child.toUri())!!.domains)
        assertTrue(child.toml("127.0.0.1:1080", "user", "password", true)
            .contains("DOMAINS = [\"dns.example.invalid\"]"))
        reject { config.copy(localPool = MasterDnsPoolSelection(listOf("a"))).validate() }
    }

    @Test fun incomingUriCannotCarryLocalIdsThroughOriginalJsonOrReExport() {
        val root = JsonParser.parseString(String(Base64.getDecoder().decode(
            config.toUri().substringAfter("://")), Charsets.UTF_8)).asJsonObject
        val injected = Gson().toJsonTree(MasterDnsPoolSelection(listOf("foreign-a", "foreign-b")))
        root.add("localPool", injected)
        root.getAsJsonObject("profile").add("tuna_pool", injected)
        root.getAsJsonObject("profile").getAsJsonObject("server").add("local_pool", injected)
        val uri = "stormdns://" + Base64.getEncoder().encodeToString(root.toString().toByteArray(Charsets.UTF_8))
        val parsed = requireNotNull(MasterDnsConfig.parse(uri))
        assertNull(parsed.localPool)
        assertFalse(parsed.originalJson.contains("foreign-"))
        val exported = String(Base64.getDecoder().decode(parsed.toUri().substringAfter("://")), Charsets.UTF_8)
        assertFalse(exported.contains("foreign-"))
        assertEquals(config.key, MasterDnsConfig.parse(parsed.toUri())!!.key)
    }
}
