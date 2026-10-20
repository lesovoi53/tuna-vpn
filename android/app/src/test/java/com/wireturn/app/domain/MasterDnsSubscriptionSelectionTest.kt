package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.MasterDnsConfig
import org.junit.Assert.*
import org.junit.Test

class MasterDnsSubscriptionSelectionTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun remote(id: String = "source-a", resolvers: String = "192.0.2.53") = Profile(id, "DNS",
        KernelConfig.MasterDns(MasterDnsConfig(domains = listOf("dns.example.invalid"), key = "synthetic-key",
            resolvers = resolvers)))
    private fun config(profile: Profile) = (profile.kernelConfig as KernelConfig.MasterDns).config
    private fun refresh(remote: Profile, local: Profile?) = SubscriptionProfileRefresh.apply(
        remote, local, local?.id ?: "local-${remote.id}", "subscription", remote.id)

    @Test fun actualRefreshHelperKeepsPersonalPoolAndUpdatesSourceAcrossRestart() {
        val initial = refresh(remote(), null)
        val edited = initial.copy(kernelConfig = KernelConfig.MasterDns(config(initial).copy(
            localResolvers = "198.51.100.53\n198.51.100.54", useLocalResolvers = true)))
        val restored = gson.fromJson(gson.toJson(edited), Profile::class.java)
        val updated = refresh(remote(resolvers = "192.0.2.54"), restored)
        assertEquals(edited.id, updated.id)
        assertEquals(listOf("198.51.100.53", "198.51.100.54"), config(updated).resolverList())
        assertEquals(listOf("192.0.2.54"), config(updated).profileResolverList())
        val backToSource = updated.copy(kernelConfig = KernelConfig.MasterDns(config(updated).copy(useLocalResolvers = false)))
        val next = refresh(remote(resolvers = "192.0.2.55"), backToSource)
        assertEquals(listOf("192.0.2.55"), config(next).resolverList())
        assertEquals(edited.kernelConfig.let { (it as KernelConfig.MasterDns).config.localResolvers }, config(next).localResolvers)
        assertEquals(listOf("198.51.100.53", "198.51.100.54"), config(next).copy(useLocalResolvers = true).resolverList())
        val baseline = gson.fromJson(next.subscriptionKernelBaseline, KernelConfig::class.java) as KernelConfig.MasterDns
        assertNull(baseline.config.localResolvers)
        assertNull(baseline.config.useLocalResolvers)
    }

    @Test fun localSelectionSurvivesLegacyRefreshWithoutBaseline() {
        val old = remote().copy(id = "local-a", subscriptionId = "subscription", subscriptionSourceId = "source-a",
            kernelConfig = KernelConfig.MasterDns(config(remote()).copy(localResolvers = "198.51.100.53", useLocalResolvers = true)))
        val next = refresh(remote(resolvers = "192.0.2.54"), old)
        assertEquals(listOf("198.51.100.53"), config(next).resolverList())
        assertEquals(listOf("192.0.2.54"), config(next).profileResolverList())
    }

    @Test fun matchingSourceIdsCannotLeakPersonalChoiceIntoAnotherProfile() {
        val a = refresh(remote(), null).let { it.copy(kernelConfig = KernelConfig.MasterDns(config(it).copy(
            localResolvers = "198.51.100.53", useLocalResolvers = true))) }
        val b = refresh(remote("source-b"), null)
        val newA = remote(resolvers = "192.0.2.54")
        val newB = remote("source-b", "192.0.2.55")
        val index = SubscriptionIdentity.Index(listOf(newA, newB), listOf(a, b))
        val refreshedB = refresh(newB, index.match(newB, emptySet()))
        val refreshedA = refresh(newA, index.match(newA, setOf(b.id)))
        assertEquals(a.id, refreshedA.id)
        assertEquals(listOf("198.51.100.53"), config(refreshedA).resolverList())
        assertEquals(b.id, refreshedB.id)
        assertNull(config(refreshedB).localResolvers)
        assertEquals(listOf("192.0.2.55"), config(refreshedB).resolverList())
        val unrelated = remote("source-c")
        assertNull(index.match(unrelated, emptySet()))
    }

    @Test fun subscriptionCannotSupplyOrReplacePersonalSelection() {
        val supplied = remote().let { it.copy(kernelConfig = KernelConfig.MasterDns(config(it).copy(
            localResolvers = "203.0.113.53", useLocalResolvers = true))) }
        val first = refresh(supplied, null)
        assertNull(config(first).localResolvers)
        assertEquals(listOf("192.0.2.53"), config(first).resolverList())
        val local = first.copy(kernelConfig = KernelConfig.MasterDns(config(first).copy(
            localResolvers = "198.51.100.53", useLocalResolvers = false)))
        val next = refresh(supplied, local)
        assertEquals("198.51.100.53", config(next).localResolvers)
        assertEquals(false, config(next).useLocalResolvers)
        assertEquals(listOf("192.0.2.53"), config(next).resolverList())
    }
}
