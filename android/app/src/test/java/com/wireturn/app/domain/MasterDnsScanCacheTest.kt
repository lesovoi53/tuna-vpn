package com.wireturn.app.domain

import com.google.gson.Gson
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.MasterDnsConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MasterDnsScanCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = MasterDnsConfig(domains = listOf("dns.example.invalid"), key = "synthetic-tunnel-key",
        resolvers = "192.0.2.53")
    private val profile = Profile("profile-a", "DNS", KernelConfig.MasterDns(config))
    private val network = MasterDnsScanCache.digest("synthetic-physical-network-a")
    private val result = MasterDnsResolverResult("192.0.2.53:53", "dns.example.invalid", true,
        upload = 64, download = 128, latencyMs = 25, attempts = 5, successes = 3, mtuCheckMs = 300)
    private fun record(now: Long) = MasterDnsSavedScan(profileStamp = MasterDnsScanCache.profileStamp(profile),
        networkStamp = network, measuredAt = now, results = listOf(result))

    @Test fun acceptsOnlySameProfileConfigurationAndPhysicalNetworkWithinTtl() {
        val now = 2_000_000_000_000L
        val saved = record(now)
        assertTrue(saved.accepts(profile, network, now))
        assertTrue(saved.accepts(profile, network, now + MasterDnsScanCache.MAX_AGE_MS))
        assertFalse(saved.accepts(profile, network, now + MasterDnsScanCache.MAX_AGE_MS + 1))
        assertFalse(saved.accepts(profile, network, now - 1))
        assertFalse(saved.accepts(profile.copy(id = "profile-b"), network, now))
        assertFalse(saved.accepts(profile, MasterDnsScanCache.digest("network-b"), now))
        assertFalse(saved.copy(version = 2).accepts(profile, network, now))
        for (changed in listOf(
            config.copy(key = "rotated-synthetic-key"),
            config.copy(domains = listOf("new.example.invalid")),
            config.copy(encryption = 4),
            config.copy(localPerformanceMode = "speed"),
            config.copy(resolvers = "192.0.2.54"),
            config.copy(settings = mapOf("MAX_UPLOAD_MTU" to "200"))
        )) {
            assertFalse(saved.accepts(profile.copy(kernelConfig = KernelConfig.MasterDns(changed)), network, now))
        }
    }

    @Test fun personalSelectionAndOpaqueSourceJsonDoNotInvalidateMeasurements() {
        val now = 2_000_000_000_000L
        val selected = profile.copy(kernelConfig = KernelConfig.MasterDns(config.copy(
            localResolvers = "198.51.100.53", useLocalResolvers = true, originalJson = """{"future":"field"}""")))
        assertTrue(record(now).accepts(selected, network, now))
        assertEquals(MasterDnsScanCache.profileStamp(profile), MasterDnsScanCache.profileStamp(selected))
    }

    @Test fun savedFileContainsNoTunnelSecretAndCannotBeReusedByAnotherProfile() {
        val directory = temporary.newFolder()
        val now = System.currentTimeMillis()
        val saved = record(now)
        MasterDnsScanCache.save(directory, profile, saved)
        val file = directory.listFiles()!!.single()
        assertFalse(file.readText().contains(config.key))
        assertEquals(saved, MasterDnsScanCache.load(directory, profile, network, now))
        assertNull(MasterDnsScanCache.load(directory, profile.copy(id = "profile-b"), network, now))
        assertNull(MasterDnsScanCache.load(directory, profile, "network-b", now))
        assertNull(MasterDnsScanCache.load(directory, profile, network, now + MasterDnsScanCache.MAX_AGE_MS + 1))
    }

    @Test fun malformedMissingOversizedAndNullResultCacheEntriesAreRejected() {
        val directory = temporary.newFolder()
        val now = System.currentTimeMillis()
        val file = File(directory, MasterDnsScanCache.digest(profile.id + network) + ".json")
        assertNull(MasterDnsScanCache.load(directory, profile, network, now))
        for (json in listOf("broken", "null", "{}", "x".repeat(1024 * 1024 + 1),
            Gson().toJson(record(now).copy(results = listOf(result.copy(attempts = 3)))),
            Gson().toJsonTree(record(now)).asJsonObject.apply {
                add("results", com.google.gson.JsonParser.parseString("[null]"))
            }.toString())) {
            file.writeText(json)
            assertNull(MasterDnsScanCache.load(directory, profile, network, now))
        }
    }

    @Test fun negativeTimestampCannotBypassTtlThroughOverflow() {
        val now = 2_000_000_000_000L
        assertFalse(record(now).copy(measuredAt = Long.MIN_VALUE).accepts(profile, network, now))
        assertFalse(record(now).copy(measuredAt = -1L).accepts(profile, network, now))
    }

    @Test fun poolThroughputRemainsBoundToProfileNetworkAndMtuScan() {
        val directory = temporary.newFolder()
        val now = System.currentTimeMillis()
        val measurement = MasterDnsPoolMeasurement(listOf(result.resolver), 262144, 2000, 524288,
            """{"packetDuplication":1}""")
        val saved = record(now).copy(poolMeasurements = listOf(measurement))
        MasterDnsScanCache.save(directory, profile, saved)
        assertEquals(saved, MasterDnsScanCache.load(directory, profile, network, now))
        val changed = profile.copy(kernelConfig = KernelConfig.MasterDns(config.copy(localPerformanceMode = "speed")))
        assertNull(MasterDnsScanCache.load(directory, changed, network, now))
        val file = directory.listFiles()!!.single()
        for (invalid in listOf(
            measurement.copy(resolvers = listOf("198.51.100.1:53")),
            measurement.copy(payloadBytes = 262145),
            measurement.copy(elapsedMs = -1),
            measurement.copy(wireBytes = -1),
            measurement.copy(effectiveParameters = "x".repeat(4097)),
            measurement.copy(resolvers = listOf(result.resolver, result.resolver))
        )) {
            file.writeText(Gson().toJson(saved.copy(poolMeasurements = listOf(invalid))))
            assertNull(MasterDnsScanCache.load(directory, profile, network, now))
        }
        file.writeText(Gson().toJson(saved.copy(poolMeasurements = listOf(measurement, measurement))))
        assertNull(MasterDnsScanCache.load(directory, profile, network, now))
    }
}
