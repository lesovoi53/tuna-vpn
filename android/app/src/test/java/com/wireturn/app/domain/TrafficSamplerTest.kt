package com.wireturn.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TrafficSamplerTest {
    @Test fun vpnTrafficRemainsVisibleWithoutXrayStatsSocket() = runBlocking {
        val sampler = TrafficSampler({ 1000 }, { longArrayOf(2, 100, 4, 400) }, { error("IPC unavailable") })
        assertEquals(TrafficSampler.Sample(400, 100), sampler.poll(true, true, false, null, "one"))
    }
    @Test fun brokenXrayIpcCannotHideRunningVpnCounters() = runBlocking {
        val sampler = TrafficSampler({ 1000 }, { longArrayOf(2, 100, 4, 400) }, { error("IPC unavailable") })
        assertEquals(TrafficSampler.Sample(400, 100), sampler.poll(true, true, false, "socket", "one"))
    }
    @Test fun proxyOnlyUsesXrayAndRejectsMalformedCounters() = runBlocking {
        var json = """{"rx_bytes":123,"tx_bytes":456}"""
        val sampler = TrafficSampler({ 1000 }, { error("VPN not running") }, { json })
        assertEquals(TrafficSampler.Sample(123, 456), sampler.poll(false, true, false, "socket", "one"))
        listOf("{}", """{"error":"failed"}""", """{"rx_bytes":-1,"tx_bytes":1}""",
            """{"rx_bytes":1.5,"tx_bytes":1}""", "").forEach {
            json = it
            assertNull(sampler.poll(false, true, false, "socket", "one"))
        }
    }
    @Test fun ratesUseElapsedTimeAndResetOnSessionOrCounterRestart() = runBlocking {
        var time = 1000L
        var counters = longArrayOf(0, 100, 0, 200)
        val sampler = TrafficSampler({ time }, { counters }, { null })
        assertEquals(0L, sampler.poll(true, false, false, null, "one")!!.rxSpeed)
        time += 2000; counters = longArrayOf(0, 2100, 0, 4200)
        assertEquals(TrafficSampler.Sample(4200, 2100, 2000, 1000), sampler.poll(true, false, false, null, "one"))
        time += 1000; counters = longArrayOf(0, 3000, 0, 5000)
        assertEquals(0L, sampler.poll(true, false, false, null, "two")!!.rxSpeed)
        time += 1000; counters = longArrayOf(0, 1, 0, 2)
        assertEquals(TrafficSampler.Sample(2, 1), sampler.poll(true, false, false, null, "two"))
    }
    @Test fun switchingSourceNeverComputesCrossSourceRates() = runBlocking {
        var time = 1000L
        val sampler = TrafficSampler({ time }, { longArrayOf(0, 90000, 0, 80000) }, { """{"rx_bytes":5,"tx_bytes":6}""" })
        sampler.poll(false, true, false, "socket", "one")
        time += 1000
        assertEquals(TrafficSampler.Sample(80000, 90000), sampler.poll(true, true, false, "socket", "one"))
        assertNull(sampler.poll(false, true, true, null, "one"))
    }
    @Test fun zeroTrafficIsValidButFailedReadingIsNotZeroTraffic() = runBlocking {
        var available = true
        val sampler = TrafficSampler({ 1000 }, { if (available) longArrayOf(0, 0, 0, 0) else null }, { null })
        assertEquals(TrafficSampler.Sample(0, 0), sampler.poll(true, true, false, null, "one"))
        available = false
        assertNull(sampler.poll(true, true, false, null, "one"))
    }
}
