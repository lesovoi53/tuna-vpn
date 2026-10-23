package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MasterDnsConfigTest {
    @Test fun domainSelectionsRoundTripAndNeverCrossProductOrFallback() {
        val config = example.copy(domains = listOf("a.example.net", "b.example.net", "off.example.net"),
            localResolvers = "9.9.9.9", useLocalResolvers = true,
            localDomainResolvers = mapOf("a.example.net" to "1.1.1.1:53", "b.example.net" to "8.8.8.8:5353"))
        config.validate()
        assertEquals(listOf("1.1.1.1:53"), config.resolverList("a.example.net"))
        assertEquals(listOf("8.8.8.8:5353"), config.resolverList("b.example.net"))
        assertTrue(config.resolverList("off.example.net").isEmpty())
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:5353"), config.resolverList())
        val restored = MasterDnsConfig.parse(config.toUri())!!
        assertEquals(config.localDomainResolvers, restored.localDomainResolvers)
        assertEquals(config.effectiveDomainResolvers(), restored.effectiveDomainResolvers())
        assertEquals(config.localDomainResolvers, Gson().fromJson(Gson().toJson(config), MasterDnsConfig::class.java).localDomainResolvers)
        val toml = config.toml("127.0.0.1:2081", "u", "p", true)
        assertTrue(toml.contains("TUNA_DOMAIN_RESOLVERS = { \"a.example.net\" = [\"1.1.1.1:53\"], \"b.example.net\" = [\"8.8.8.8:5353\"], \"off.example.net\" = [] }"))
        val source = config.copy(useLocalResolvers = false)
        assertEquals(example.profileResolverList(), source.resolverList("off.example.net"))
        assertFalse(source.toml("127.0.0.1:2081", "u", "p", true).contains("TUNA_DOMAIN_RESOLVERS"))
        assertFalse(config.copy(localDomainResolvers = emptyMap()).isValid())
        assertFalse(config.copy(localDomainResolvers = mapOf("a.example.net" to "1.1.1.1:0")).isValid())
    }

    @Test fun legacySharedResolverSelectionStillAppliesToEveryDomain() {
        val config = example.copy(domains = listOf("a.example.net", "b.example.net"),
            localResolvers = "9.9.9.9", useLocalResolvers = true)
        assertEquals(listOf("9.9.9.9"), config.resolverList("a.example.net"))
        assertEquals(config.resolverList("a.example.net"), config.resolverList("b.example.net"))
        assertFalse(config.toml("127.0.0.1:2081", "u", "p", true).contains("TUNA_DOMAIN_RESOLVERS"))
    }

    @Test fun oneTwoFourDomainsSurviveExportStorageAndNativeConfig() {
        for (count in listOf(1, 2, 4)) {
            val domains = (0 until count).map { "d$it.example.invalid" }
            val original = example.copy(domains = domains)
            val root = JsonParser.parseString(String(Base64.getDecoder().decode(original.toUri().substringAfter("://")))).asJsonObject
            val profile = root.getAsJsonObject("profile")
            assertEquals(domains, profile.getAsJsonArray("domains").map { it.asString })
            assertEquals(domains.first(), profile.getAsJsonObject("server")["domain"].asString)
            val imported = MasterDnsConfig.parse(original.toUri())!!
            val restored = Gson().fromJson(Gson().toJson(imported), MasterDnsConfig::class.java)
            assertEquals(domains, restored.domains)
            assertEquals(original.key, restored.key)
            assertEquals(original.resolvers, restored.resolvers)
            assertEquals(original.settings, restored.settings)
            assertTrue(restored.toml("127.0.0.1:2081", "u", "p", true)
                .lineSequence().contains("DOMAINS = ${Gson().toJson(domains)}"))
        }
    }
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

    @Test fun personalSelectionSurvivesStorageExportAndSwitchBack() {
        val selected = example.copy(localResolvers = "192.0.2.53\n192.0.2.54\n192.0.2.53", useLocalResolvers = true)
        val restored = Gson().fromJson(Gson().toJson(selected), MasterDnsConfig::class.java)
        assertEquals(listOf("192.0.2.53", "192.0.2.54"), restored.resolverList())
        assertEquals(example.resolverList(), restored.profileResolverList())
        val imported = MasterDnsConfig.parse(restored.toUri())!!
        assertEquals(selected.localResolvers, imported.localResolvers)
        assertEquals(selected.resolverList(), imported.resolverList())
        val source = MasterDnsConfig.parse(imported.copy(useLocalResolvers = false).toUri())!!
        assertEquals(example.resolverList(), source.resolverList())
        assertEquals(selected.localResolvers, source.localResolvers)
        assertEquals(selected.resolverList(), source.copy(useLocalResolvers = true).resolverList())
    }

    @Test fun legacyGsonAndUriUseTheOriginalPool() {
        val old = Gson().toJsonTree(example).asJsonObject.apply {
            remove("localResolvers"); remove("useLocalResolvers")
        }
        val restored = Gson().fromJson(old, MasterDnsConfig::class.java)
        assertNull(restored.localResolvers)
        assertNull(restored.useLocalResolvers)
        assertEquals(example.resolverList(), restored.resolverList())
        assertEquals(example.resolverList(), MasterDnsConfig.parse(example.toUri())!!.resolverList())
    }

    @Test fun invalidSelectionCannotHideAnInvalidSourceOrRunAnEmptyPool() {
        assertFalse(example.copy(localResolvers = "", useLocalResolvers = true).isValid())
        assertFalse(example.copy(localResolvers = "https://example.invalid", useLocalResolvers = false).isValid())
        assertFalse(example.copy(resolvers = "", localResolvers = "192.0.2.53", useLocalResolvers = true).isValid())
        val selected = MasterDnsConfig.parse(example.copy(localResolvers = "192.0.2.53", useLocalResolvers = true).toUri())!!
        val cleared = MasterDnsConfig.parse(selected.copy(localResolvers = null, useLocalResolvers = null).toUri())!!
        assertNull(cleared.localResolvers)
        assertNull(cleared.useLocalResolvers)
        assertEquals(example.resolverList(), cleared.resolverList())
    }
}
