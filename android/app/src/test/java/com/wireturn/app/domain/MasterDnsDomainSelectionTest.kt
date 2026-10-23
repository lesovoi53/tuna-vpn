package com.wireturn.app.domain

import com.wireturn.app.data.kernel.MasterDnsConfig
import org.junit.Assert.*
import org.junit.Test

class MasterDnsDomainSelectionTest {
    private val a = "a.example.invalid"
    private val b = "b.example.invalid"
    private val config = MasterDnsConfig(domains = listOf(a, b), key = "synthetic",
        resolvers = "192.0.2.53", localResolvers = "198.51.100.53", useLocalResolvers = true)

    @Test fun selectingSameResolverForOneDomainDoesNotSelectItForAnother() {
        val scoped = config.copy(localDomainResolvers = mapOf(a to "192.0.2.53", b to "198.51.100.53"))
        val saved = MasterDnsDomainSelection.saved(scoped)
        assertEquals(setOf("192.0.2.53:53"), saved[a])
        assertEquals(setOf("198.51.100.53:53"), saved[b])
    }

    @Test fun applyingOneDomainPreservesOtherExplicitlyDisabledDomain() {
        val scoped = config.copy(localDomainResolvers = mapOf(a to "192.0.2.53", b to ""), useLocalResolvers = false)
        val changed = MasterDnsDomainSelection.merge(scoped, a, setOf("203.0.113.53:53"))
        assertEquals("203.0.113.53:53", changed[a])
        assertEquals("", changed[b])
    }

    @Test fun firstMigrationRetainsOtherLegacyLocalResolvers() {
        val changed = MasterDnsDomainSelection.merge(config, a, setOf("203.0.113.53:53"))
        assertEquals("198.51.100.53", changed[b])
    }

    @Test fun disablingDomainRequiresAnotherUsableDomain() {
        assertEquals("", MasterDnsDomainSelection.merge(config, a, emptySet())[a])
        val oneActive = config.copy(localDomainResolvers = mapOf(a to "192.0.2.53", b to ""))
        assertThrows(IllegalArgumentException::class.java) { MasterDnsDomainSelection.merge(oneActive, a, emptySet()) }
    }
    @Test fun lowerCaseNativeDomainMapsBackToProfileSpelling() {
        val mixed = config.copy(domains = listOf("A.Example.Invalid", b))
        assertEquals("A.Example.Invalid", MasterDnsDomainSelection.profileDomain(mixed, "a.example.invalid"))
        assertNull(MasterDnsDomainSelection.profileDomain(mixed, "unknown.example.invalid"))
    }

    @Test fun workingSelectionIncludesEveryCompletedWorkingPairWithoutSpeedRanking() {
        val fast = MasterDnsResolverResult("192.0.2.1", a, true, 200, 900, 5, 5, 5)
        val slow = MasterDnsResolverResult("192.0.2.2", a, true, 28, 60, 9000, 5, 1)
        val failed = fast.copy(resolver = "192.0.2.3", valid = false)
        val partial = fast.copy(resolver = "192.0.2.4", attempts = 1)
        val other = fast.copy(resolver = "192.0.2.5", domain = b)
        assertEquals(setOf("192.0.2.1:53", "192.0.2.2:53"),
            MasterDnsDomainSelection.working(listOf(fast, slow, failed, partial, other), a))
    }

    @Test fun workingSelectionDeduplicatesCanonicalAddressesAndRespectsDomainCase() {
        val first = MasterDnsResolverResult("192.0.2.1", a.uppercase(), true, 64, 128, 20, 5, 5)
        assertEquals(setOf("192.0.2.1:53"),
            MasterDnsDomainSelection.working(listOf(first, first.copy(resolver = "192.0.2.1:53")), a))
    }

}
