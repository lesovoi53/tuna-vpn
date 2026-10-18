package com.wireturn.app.kernel

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class TunAddressPolicyTest {
    private fun prefix(ip: String, length: Int) = TunAddressPolicy.Prefix(InetAddress.getByName(ip).address, length)
    @Test fun avoidsPhysicalLanAndRetainsValidFamilies() {
        val occupied = listOf(prefix("10.0.0.0", 8), prefix("172.16.0.0", 12), prefix("0.0.0.0", 0), prefix("fd12::", 16))
        repeat(20) {
            val result = TunAddressPolicy.choose(occupied)
            assertTrue(result.ipv4.startsWith("192.168."))
            assertEquals(16, InetAddress.getByName(result.ipv6).address.size)
            assertFalse(occupied.last().contains(InetAddress.getByName(result.ipv6).address))
        }
    }
    @Test fun exhaustionFailsInsteadOfUsingConflictingAddress() {
        val occupied = listOf(prefix("10.0.0.0", 8), prefix("172.16.0.0", 12), prefix("192.168.0.0", 16))
        assertTrue(runCatching { TunAddressPolicy.choose(occupied) }.isFailure)
        assertTrue(runCatching { TunAddressPolicy.choose(listOf(prefix("fd00::", 8))) }.isFailure)
    }
    @Test fun partialPrefixBoundariesAreRespected() {
        val occupied = prefix("172.16.0.0", 12)
        assertTrue(occupied.contains(InetAddress.getByName("172.31.255.255").address))
        assertFalse(occupied.contains(InetAddress.getByName("172.32.0.0").address))
    }
}
