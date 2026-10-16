package com.wireturn.app.domain

import com.wireturn.app.*
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** Offline regression gate for the five RC3 boundary defects. */
class Rc3AdversarialAuditTest {
    private val base = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=tls"
    private fun profile(id: String, name: String = "Server $id") = Profile(id, name, KernelConfig.Vpn(VpnConfig("$base&fp=firefox")))
    private fun source(match: String, ip: Boolean = true) = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(
        """{"Name":"Audit","RemoteDNSType":"UDP","RemoteDns":"192.0.2.53","DomesticDNSType":"UDP","DomesticDns":"192.0.2.54","${if(ip) "DirectIp" else "BlockSites"}":["$match"]}""".toByteArray())
    private fun database(category: String): ByteArray = category.toByteArray().let {
        byteArrayOf(10, (it.size + 2).toByte(), 10, it.size.toByte()) + it
    }
    private fun assets(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("tuna-synthetic-rc3-").toFile()
        try {
            File(dir, "geoip.dat").writeBytes(database("PRIVATE"))
            File(dir, "geosite.dat").writeBytes(database("DEMO"))
            block(dir)
        } finally { dir.deleteRecursively() }
    }
    @Test fun inverseGeoIpCanUseTheExistingBaseCategory() = assets { dir ->
        SubscriptionRoutingAssets.validateDirectory(dir, source("geoip:!private"))
    }
    @Test fun geoCategoryCaseMatchesXraySemantics() = assets { dir ->
        SubscriptionRoutingAssets.validateDirectory(dir, source("geoip:PRIVATE"))
    }
    @Test fun positiveGeoIpAndAttributedGeositeAreAccepted() = assets { dir ->
        SubscriptionRoutingAssets.validateDirectory(dir, source("geoip:private"))
        SubscriptionRoutingAssets.validateDirectory(dir, source("geosite:demo@ads", false))
    }
    @Test fun absentGeoCategoryStillBlocksPublication() = assets { dir ->
        assertTrue(runCatching { SubscriptionRoutingAssets.validateDirectory(dir, source("geoip:missing")) }.isFailure)
    }
    @Test fun invalidRegexpCannotBeSavedAsValidRouting() {
        val policy = RoutingPolicy(rules = listOf(RoutingRule("regexp:[", RouteAction.BLOCK)))
        assertTrue("Invalid regexp currently passes the editor's validation", runCatching { policy.validate() }.isFailure)
    }
    @Test fun explicitEmptyFingerprintUsesVerifiedImportFallback() {
        val imported = VlessFingerprintImport.vpn(requireNotNull(VpnConfig.parse("$base&fp="))) { it.first() }
        assertTrue("An empty fp must not resolve to Xray's default Chrome", VlessFingerprintChoices.effective(imported) in VlessFingerprintChoices.verified("xray"))
    }
    @Test fun omittedAndBlockedFingerprintUseVerifiedImportFallback() {
        for (suffix in listOf("", "&fp=chrome", "&fp=random", "&fp=randomized")) {
            val imported = VlessFingerprintImport.vpn(requireNotNull(VpnConfig.parse(base + suffix))) { it.first() }
            assertEquals("firefox", VlessFingerprintChoices.effective(imported))
        }
    }
    @Test fun nonFatalErrorMetadataMustNotLookFatal() {
        assertFalse(LogLevel.FATAL.accepts("[qWDTT] error fatal=false: DNS timeout"))
    }
    @Test fun errorCountersWithoutErrorsMustNotPassErrorFilter() {
        assertFalse("Severity should not be inferred from a counter field", LogLevel.ERROR.accepts("[Info] stats: errors=0, failed=0, active=18"))
    }
    @Test fun oneTwoFourEightParallelSlotsPreserveAllResults() = runBlocking {
        for (slots in listOf(1, 2, 4, 8)) {
            val members = (1..40).map { profile(it.toString()) }
            val batch = ProfileUrlTestBatch(parallelism = slots)
            var active = 0; var peak = 0; var cleaned = 0
            batch.run("audit-$slots", members, { members }) {
                active++; peak = maxOf(peak, active)
                try { delay(2); if (it.id.toInt() % 5 == 0) error("synthetic failure"); 12L }
                finally { active--; cleaned++ }
            }
            assertEquals(slots, peak); assertEquals(40, cleaned); assertEquals(40, batch.state.value.completed)
            assertEquals(32, batch.state.value.results.values.count { it.label == "12 мс" })
            assertFalse(batch.state.value.busy)
        }
    }
    @Test fun bypassNamedVlessIsIncludedButBypassCoreIsExcludedFromSubscriptionUrlBatch() = runBlocking {
        val members = listOf(profile("a", "Москва обход БС"), profile("b"),
            Profile("c", "Обход БС qWDTT", KernelConfig.Qwdtt()))
        val visited = mutableListOf<String>()
        val batch = ProfileUrlTestBatch()
        batch.run("sub", members, { members }) { visited += it.id; 1L }
        assertEquals(listOf("a", "b"), visited)
        assertEquals(setOf("a", "b"), batch.state.value.results.keys)
        assertEquals("1 мс", batch.state.value.labelFor(members.first()))
    }
    @Test fun bypassNamedProfilesMustNotBeProbedByImportedVpnGroup() = runBlocking {
        val members = listOf(profile("a", "Москва обход БС"), profile("b", "Обход БС 3"))
        val group = ConnectionGroup("g", "Imported VPN", ConnectionCategory.VPN, GroupTestType.URL_TEST, listOf("a", "b"))
        val measured = mutableListOf<String>()
        val runtime = object : GroupRuntime {
            override val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
            override suspend fun profiles() = members
            override suspend fun groups() = listOf(group)
            override suspend fun currentId() = "a"
            override suspend fun activeGroupId(): String? = null
            override suspend fun selectGroup(id: String) {}
            override fun network(allowMobile: Boolean) = setOf("synthetic-wifi")
            override suspend fun activate(profile: Profile, routing: Profile) = ClientConfig(kernelConfig = profile.kernelConfig)
            override suspend fun <T> withTestTunnel(profile: Profile, block: suspend (ClientConfig) -> T): T = block(ClientConfig(kernelConfig = profile.kernelConfig))
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                measured += id; GroupMeasurement(id, 1L, measuredAt = System.currentTimeMillis())
            }
            runner.test(group)
            withTimeout(2000) { while (runner.state.value.busy) yield() }
            assertTrue("Runner measured bypass members: $measured", measured.isEmpty())
        } finally { scope.cancel() }
    }
}
