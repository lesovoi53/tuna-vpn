package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test

class DnsRuntimeTelemetryTest {
    @Test fun emergencyRecordsUseZeroIndexAndExplicitSource() {
        for (group in listOf("remote", "domestic")) {
            for (reason in listOf("emergency-system", "emergency-resolver", "emergency-system-negative", "emergency-resolver-negative")) {
                val observation = DnsRuntimeTelemetry.parse("TUNA_DNS_STATUS $group 0 $reason")
                assertNotNull("Missing emergency observation: $reason", observation)
                assertEquals(0, observation!!.index)
                assertEquals(reason, observation.reason)
            }
        }
        for (line in listOf("TUNA_DNS_STATUS remote 0 selected", "TUNA_DNS_STATUS remote 1 emergency-system",
            "TUNA_DNS_STATUS domestic 8 emergency-resolver-negative", "TUNA_DNS_STATUS remote 0 emergency-unknown")) {
            assertNull(line, DnsRuntimeTelemetry.parse(line))
        }
    }

    @Test fun acceptsOnlyBoundedStructuredRecords() {
        assertEquals(2, DnsRuntimeTelemetry.parse("TUNA_DNS_STATUS remote 2 fallback")?.index)
        listOf("TUNA_DNS_STATUS remote 9 selected", "TUNA_DNS_STATUS arbitrary 1 selected",
            "server says TUNA_DNS_STATUS remote 1 selected", "TUNA_DNS_STATUS remote 1 selected secret").forEach {
            assertNull(DnsRuntimeTelemetry.parse(it))
        }
    }
    @Test fun oldProcessCannotOverwriteOrClearNewProcess() {
        val old = DnsRuntimeTelemetry.begin()
        val fresh = DnsRuntimeTelemetry.begin()
        DnsRuntimeTelemetry.accept(fresh, "TUNA_DNS_STATUS remote 2 fallback")
        DnsRuntimeTelemetry.accept(old, "TUNA_DNS_STATUS remote 1 selected")
        DnsRuntimeTelemetry.end(old)
        assertEquals(2, DnsRuntimeTelemetry.observations.value["remote"]?.index)
        DnsRuntimeTelemetry.end(fresh)
        assertTrue(DnsRuntimeTelemetry.observations.value.isEmpty())
    }
}
