package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test

class MasterDnsResolverRecommendationTest {
    private fun path(n: Int, down: Int = 900, rtt: Long = 60, success: Int = 5,
                     domain: String = "dns.example.invalid") =
        MasterDnsResolverResult("192.0.2.$n:53", domain, true, 110, down, rtt, 5, success)

    @Test fun lowMtuFastPathDoesNotDragDownHealthyPool() {
        val results = listOf(path(1, down = 100, rtt = 40), path(2), path(3, rtt = 65))
        assertEquals(listOf("192.0.2.2:53", "192.0.2.3:53"), MasterDnsResolverRecommendation.recommend(results))
    }

    @Test fun recommendationIsNotCappedAtThreeAndExcludesSevereLoss() {
        val results = (1..12).map { path(it) } + path(13, success = 1)
        assertEquals(12, MasterDnsResolverRecommendation.recommend(results).size)
        val candidates = MasterDnsResolverRecommendation.candidatePools(results, listOf("192.0.2.1", "192.0.2.2"))
        assertEquals(listOf(2, 1, 4, 12), candidates.map { it.size })
    }

    @Test fun baselineIsCanonicalizedAndUnknownOrFailedEndpointsAreNotProbed() {
        val results = listOf(path(1), path(2).copy(valid = false), path(3).copy(attempts = 0))
        assertEquals(listOf(listOf("192.0.2.1:53")),
            MasterDnsResolverRecommendation.candidatePools(results, listOf("192.0.2.1", "192.0.2.2", "192.0.2.9")))
        assertTrue(MasterDnsResolverRecommendation.candidatePools(emptyList(), emptyList()).isEmpty())
    }

    @Test fun multiDomainPathsUseWorstHealthyMtuAndDeduplicateEndpoints() {
        val results = listOf(path(1), path(1, down = 80, domain = "other.example.invalid"), path(2), path(3))
        assertEquals(setOf("192.0.2.2:53", "192.0.2.3:53"),
            MasterDnsResolverRecommendation.recommend(results).toSet())
    }

    @Test fun fullRangeRemainsAvailableWithinFourTrials() {
        val pools = MasterDnsResolverRecommendation.candidatePools((1..128).map { path(it) }, emptyList())
        assertEquals(listOf(1, 12, 128), pools.map { it.size })
        assertTrue(pools.size <= MasterDnsResolverRecommendation.MAX_POOLS)
    }

    @Test fun poolsMatchRuntimeOnePortPerIpAndKeepBaselineFirstPort() {
        val results = listOf(path(1, rtt = 80), path(1, rtt = 50).copy(resolver = "192.0.2.1:5353"), path(2))
        assertEquals(listOf("192.0.2.1:5353", "192.0.2.2:53"), MasterDnsResolverRecommendation.recommend(results))
        val pools = MasterDnsResolverRecommendation.candidatePools(results, listOf("192.0.2.1:53", "192.0.2.1:5353"))
        assertEquals(listOf("192.0.2.1:53"), pools.first())
        assertTrue(pools.all { pool -> pool.map { MasterDnsEndpoint.parse(it)!!.host }.distinct().size == pool.size })
    }

    @Test fun insignificantSpeedGainKeepsBaselineAndFailuresNeverWin() {
        fun sample(n: Int, millis: Long, error: String? = null) =
            MasterDnsPoolMeasurement(listOf("192.0.2.$n:53"), 262144, millis, error = error)
        val baseline = sample(1, 1000)
        assertEquals(baseline, MasterDnsResolverRecommendation.winner(listOf(baseline, sample(2, 950))))
        val faster = sample(3, 800)
        assertEquals(faster, MasterDnsResolverRecommendation.winner(listOf(baseline, faster, sample(4, 1, "failed"))))
        assertNull(MasterDnsResolverRecommendation.winner(listOf(sample(1, 0), sample(2, 100).copy(payloadBytes = 1))))
    }
}
