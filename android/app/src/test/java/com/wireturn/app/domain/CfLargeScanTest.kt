package com.wireturn.app.domain

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CfLargeScanTest {
    @Test fun generates5000UniqueCloudflareAddressesAndKeepsPriority() {
        val priority = listOf("104.16.1.1", "104.16.1.1", "192.0.2.1")
        val addresses = CfAddress.candidates(5000, priority = priority, random = Random(42))
        assertEquals(5000, addresses.size)
        assertEquals(5000, addresses.toSet().size)
        assertEquals("104.16.1.1", addresses.first())
        addresses.forEach { address ->
            val ip = CfAddress.literal(address)!!
            assertEquals(4, ip.address.size)
            assertTrue(CfAddress.bundled.any { it.contains(ip) })
        }
        for (count in listOf(0, 5001)) {
            assertThrows(IllegalArgumentException::class.java) { CfSettings(count = count) }
            assertThrows(IllegalArgumentException::class.java) { CfAddress.candidates(count) }
        }
    }

    @Test fun whole5000BatchProgressCompletesWithoutLaunchingCoresForClosedPorts() = runBlocking {
        var received = 0
        var last: CfScanProgress? = null
        val result = withTimeout(15_000) {
            CfScanPipeline().run((1..5000).map { "$it" }, CfSettings(count = 5000, quick = false),
                tcp = { false }, probe = { _, _ -> error("Closed ports must not start cores") },
                onResult = { received++ }, onProgress = { last = it })
        }
        assertFalse(result.timedOut)
        assertEquals(5000, received)
        assertEquals(5000, last!!.tcpChecked)
        assertEquals(5000, last!!.completed)
        assertEquals(0, last!!.confirmed)
    }

    @Test fun largeResultSetKeepsUiAndStorageBoundedAndFailedRetestsRemoveOldChoices() {
        var visible = emptyList<CfResult>()
        var cache = emptyList<CfResult>()
        repeat(5000) { index ->
            val result = CfResult("$index", listOf(index.toLong() + 1, index.toLong() + 2), 2)
            visible = CfResultCache.visible(visible, result)
            cache = CfResultCache.merge(cache, listOf(result))
            assertTrue(visible.size <= 256)
            assertTrue(cache.size <= 256)
        }
        assertEquals((0..255).map { "$it" }, cache.map { it.ip })
        cache = CfResultCache.merge(cache, listOf(CfResult("0", emptyList(), 0, "TCP")))
        assertEquals(255, cache.size)
        assertFalse(cache.any { it.ip == "0" })
    }
}
