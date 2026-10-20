package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsPoolSelection
import org.junit.Assert.*
import org.junit.Test

class MasterDnsPoolRefreshTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun source(id: String = "a", key: String = "synthetic-key") = Profile(id, "DNS $id",
        KernelConfig.MasterDns(MasterDnsConfig(domains = listOf("$id.example.invalid"),
            key = key, resolvers = "192.0.2.53")))
    private fun config(profile: Profile) = (profile.kernelConfig as KernelConfig.MasterDns).config
    private fun refresh(incoming: Profile, old: Profile?) = SubscriptionProfileRefresh.apply(
        incoming, old, old?.id ?: "local-${incoming.id}", "subscription", incoming.id)

    @Test fun refreshKeepsLocalMembershipAcrossRestartWhileUpdatingEachServersCredentials() {
        for (baseline in listOf(true, false)) {
            val initialA = refresh(source(), null)
            val initialB = refresh(source("b"), null)
            val selection = MasterDnsPoolSelection(listOf(initialA.id, initialB.id), "round_robin")
            val local = initialA.copy(kernelConfig = KernelConfig.MasterDns(config(initialA).copy(
                localPool = selection, localResolvers = "198.51.100.53", useLocalResolvers = true,
                localPerformanceMode = "speed")),
                subscriptionKernelBaseline = if (baseline) initialA.subscriptionKernelBaseline else null)
            val restored = gson.fromJson(gson.toJson(local), Profile::class.java)
            val updatedA = refresh(source(key = "rotated-a"), restored)
            val updatedB = refresh(source("b", "rotated-b"), initialB)
            assertEquals(selection, config(updatedA).localPool)
            assertEquals("198.51.100.53", config(updatedA).localResolvers)
            assertEquals(true, config(updatedA).useLocalResolvers)
            assertEquals("speed", config(updatedA).localPerformanceMode)
            val members = listOf(updatedA, updatedB)
            val plan = MasterDnsPoolPlan.resolve(updatedA, members, members.associate { it.id to SharedNetworkSettings() })!!
            assertEquals(listOf("rotated-a", "rotated-b"), plan.members.map { it.config.key })
            val remoteBaseline = gson.fromJson(updatedA.subscriptionKernelBaseline, KernelConfig::class.java) as KernelConfig.MasterDns
            assertNull(remoteBaseline.config.localPool)
            val disabled = updatedA.copy(kernelConfig = KernelConfig.MasterDns(config(updatedA).copy(localPool = null)))
            assertNull(config(refresh(source(), disabled)).localPool)
        }
    }

    @Test fun incomingSubscriptionCannotSetOrReplaceLocalMembershipEvenWithInvalidForeignPool() {
        val incoming = source().let { it.copy(kernelConfig = KernelConfig.MasterDns(config(it).copy(
            localPool = MasterDnsPoolSelection(listOf("foreign"), "unsupported")))) }
        val initial = refresh(incoming, null)
        assertNull(config(initial).localPool)
        for (baseline in listOf(true, false)) {
            val localSelection = MasterDnsPoolSelection(listOf(initial.id, "local-b"))
            val old = initial.copy(kernelConfig = KernelConfig.MasterDns(config(initial).copy(localPool = localSelection)),
                subscriptionKernelBaseline = if (baseline) initial.subscriptionKernelBaseline else null)
            assertEquals(localSelection, config(refresh(incoming, old)).localPool)
            assertEquals(localSelection, (SubscriptionProfileMerge.kernel(old, incoming) as KernelConfig.MasterDns).config.localPool)
            assertNull((SubscriptionProfileMerge.kernel(initial.copy(subscriptionKernelBaseline =
                if (baseline) initial.subscriptionKernelBaseline else null), incoming) as KernelConfig.MasterDns).config.localPool)
        }
    }
}
