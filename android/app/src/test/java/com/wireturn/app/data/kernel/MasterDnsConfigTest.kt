package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MasterDnsConfigTest {
    private val example = MasterDnsConfig("Example",listOf("dns.example.net"),"example-only-key",5,"1.1.1.1\n8.8.8.8",
        mapOf("RX_TX_WORKERS" to "4", "TUNNEL_PROCESS_WORKERS" to "0", "BASE_ENCODE_DATA" to "true", "PACKET_DUPLICATION_COUNT" to "4",
            "SETUP_PACKET_DUPLICATION_COUNT" to "4", "MIN_UPLOAD_MTU" to "28", "MAX_UPLOAD_MTU" to "200", "MIN_DOWNLOAD_MTU" to "60", "MAX_DOWNLOAD_MTU" to "900",
            "ARQ_WINDOW_SIZE" to "600", "ARQ_INITIAL_RTO_SECONDS" to "2", "ARQ_MAX_RTO_SECONDS" to "5", "PING_AGGRESSIVE_INTERVAL_SECONDS" to "0.05", "PING_LAZY_INTERVAL_SECONDS" to "0.5"))

    @Test fun profileImportPreservesExplicitZerosFractionsAndUnknownFields() {
        val uri=example.toUri()
        val root=JsonParser.parseString(String(Base64.getDecoder().decode(uri.substringAfter("://")))) .asJsonObject
        root.getAsJsonObject("profile").addProperty("future_setting", "preserve")
        val parsed=MasterDnsConfig.parse("stormdns://"+Base64.getEncoder().encodeToString(root.toString().toByteArray()))!!
        assertEquals(example.settings,parsed.settings)
        assertEquals(example.domains,parsed.domains)
        assertEquals(example.resolvers,parsed.resolvers)
        val exported=JsonParser.parseString(String(Base64.getDecoder().decode(parsed.toUri().substringAfter("://")))).asJsonObject
        assertEquals("preserve",exported.getAsJsonObject("profile")["future_setting"].asString)
        val toml=parsed.toml("127.0.0.1:2081","u","p",true)
        assertTrue(toml.contains("TUNNEL_PROCESS_WORKERS = 0"))
        assertTrue(toml.contains("PING_AGGRESSIVE_INTERVAL_SECONDS = 0.05"))
        assertTrue(toml.contains("SOCKS5_AUTH = true"))
        assertTrue(toml.contains("LOCAL_DNS_ENABLED = false"))
        assertFalse(toml.contains("future_setting"))
    }
    @Test fun everyExposedFieldIsValidatedAndWrittenToNativeConfig() {
        val all=MasterDnsFields.all.associate { it.key to it.defaultValue }
        val config=example.copy(settings=all)
        val parsed=MasterDnsConfig.parse(config.toUri())!!
        assertEquals(all,parsed.settings)
        val toml=parsed.toml("127.0.0.1:2081","u","p",true)
        MasterDnsFields.all.forEach { assertTrue(it.key,toml.contains("${it.key} = ")) }
        assertEquals(MasterDnsFields.all.size,MasterDnsFields.byKey.size)
        // Non-secret fixture for the pinned native config loader's independent validation.
        val directory=java.io.File("build/masterdns-fixture").apply { mkdirs() }
        java.io.File(directory,"client.toml").writeText(toml)
        java.io.File(directory,"client_resolvers.txt").writeText(parsed.resolvers)
    }
    @Test fun rejectsUnsafeValuesAndGroupsAtDataBoundary() {
        assertFalse(example.copy(settings=mapOf("MTU_SERVERS_FILE_NAME" to "../outside")).isValid())
        assertFalse(example.copy(settings=mapOf("MTU_TEST_TIMEOUT" to "NaN")).isValid())
        assertFalse(example.copy(settings=mapOf("MIN_UPLOAD_MTU" to "500", "MAX_UPLOAD_MTU" to "100")).isValid())
        assertThrows(IllegalArgumentException::class.java) { example.toml("0.0.0.0:2081","u","p",true) }
        val a=Profile("a","DNS",KernelConfig.MasterDns(example))
        val b=Profile("b","Other",KernelConfig.Qwdtt())
        val g=ConnectionGroup("g","Forbidden",ConnectionCategory.BYPASS,GroupTestType.URL_TEST,listOf("a","b"))
        for (type in GroupTestType.entries) assertThrows(IllegalArgumentException::class.java) { g.copy(type=type).validate(listOf(a,b)) }
    }
    @Test fun importedKernelSurvivesStorageAndProfileNormalization() {
        val parsed=KernelConfig.parseUri(example.toUri())!!
        assertTrue(parsed is KernelConfig.MasterDns)
        val gson=com.google.gson.GsonBuilder().registerTypeAdapter(KernelConfig::class.java,KernelConfigAdapter()).create()
        val restored=gson.fromJson(gson.toJson(Profile("id","DNS",parsed)),Profile::class.java)
        assertEquals(parsed,restored.kernelConfig)
    }
    @Test fun resetAnImportedOverrideDoesNotReintroduceItOnExport() {
        val imported=MasterDnsConfig.parse(example.toUri())!!
        val reset=imported.copy(settings=imported.settings-"MIN_UPLOAD_MTU")
        val restored=MasterDnsConfig.parse(reset.toUri())!!
        assertFalse(restored.settings.containsKey("MIN_UPLOAD_MTU"))
        assertEquals("38",restored.value(MasterDnsFields.byKey.getValue("MIN_UPLOAD_MTU")))
    }
}
