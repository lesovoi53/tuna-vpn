package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsFields
import com.wireturn.app.data.kernel.MasterDnsPerformanceMode
import org.junit.Assert.*
import org.junit.Test

class MasterDnsPerformanceRefreshTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val source = Profile("source", "DNS", KernelConfig.MasterDns(MasterDnsConfig(
        domains = listOf("dns.example.invalid"), key = "synthetic-key", resolvers = "192.0.2.53",
        settings = mapOf("PACKET_DUPLICATION_COUNT" to "5"))))
    private fun config(profile: Profile) = (profile.kernelConfig as KernelConfig.MasterDns).config
    private fun refresh(incoming: Profile, existing: Profile?) =
        SubscriptionProfileRefresh.apply(incoming, existing, existing?.id ?: "local", "subscription", incoming.id)

    @Test fun actualRefreshPreservesModeAcrossRestartWhileUpdatingSourceParameters() {
        MasterDnsPerformanceMode.entries.forEach { mode ->
            for (hasBaseline in listOf(true, false)) {
                val initial = refresh(source, null)
                val local = initial.copy(
                    kernelConfig = KernelConfig.MasterDns(config(initial).copy(localPerformanceMode = mode.wireValue)),
                    subscriptionKernelBaseline = if (hasBaseline) initial.subscriptionKernelBaseline else null)
                val restored = gson.fromJson(gson.toJson(local), Profile::class.java)
                val incoming = source.copy(kernelConfig = KernelConfig.MasterDns(config(source).copy(
                    key = "rotated-synthetic-key", resolvers = "192.0.2.54",
                    settings = mapOf("PACKET_DUPLICATION_COUNT" to "7"))))
                val updated = refresh(incoming, restored)
                assertEquals(local.id, updated.id)
                assertEquals(mode.wireValue, config(updated).localPerformanceMode)
                assertEquals("rotated-synthetic-key", config(updated).key)
                assertEquals("192.0.2.54", config(updated).resolvers)
                val duplication = MasterDnsFields.byKey.getValue("PACKET_DUPLICATION_COUNT")
                assertEquals("7", config(updated).value(duplication))
                assertEquals(mode.packetDuplication.toString(), config(updated).effectiveValue(duplication))
                val reverted = updated.copy(kernelConfig = KernelConfig.MasterDns(config(updated).copy(localPerformanceMode = null)))
                val next = refresh(incoming, reverted)
                assertNull(config(next).localPerformanceMode)
                assertEquals("7", config(next).effectiveValue(duplication))
                val baseline = gson.fromJson(updated.subscriptionKernelBaseline, KernelConfig::class.java) as KernelConfig.MasterDns
                assertNull(baseline.config.localPerformanceMode)
            }
        }
    }

    @Test fun subscriptionCannotSupplyModeForNewOrExistingProfiles() {
        val supplied = source.copy(kernelConfig = KernelConfig.MasterDns(config(source).copy(localPerformanceMode = "stable")))
        val initial = refresh(supplied, null)
        assertNull(config(initial).localPerformanceMode)
        assertNull(config(refresh(supplied, initial)).localPerformanceMode)
        val chosen = initial.copy(kernelConfig = KernelConfig.MasterDns(config(initial).copy(localPerformanceMode = "speed")))
        assertEquals("speed", config(refresh(supplied, chosen)).localPerformanceMode)
    }
}
