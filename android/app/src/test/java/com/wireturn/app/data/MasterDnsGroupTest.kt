package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsPoolSelection
import org.junit.Assert.*
import org.junit.Test

class MasterDnsGroupTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun profile(id: String, sub: String? = null) = Profile(id, "DNS $id",
        KernelConfig.MasterDns(MasterDnsConfig(domains = listOf("$id.example.invalid"),
            key = "synthetic-$id", resolvers = "192.0.2.53")), subscriptionId = sub)
    private val group = MasterDnsGroup("group", "Мои DNS", listOf("a", "b"))

    @Test fun persistedGroupContainsReferencesAndNoServerConfiguration() {
        val encoded = gson.toJson(group.copy(subscriptionId = "sub"))
        val json = JsonParser.parseString(encoded).asJsonObject
        assertEquals(setOf("id", "name", "memberIds", "strategy", "subscriptionId"), json.keySet())
        assertEquals(group, gson.fromJson(gson.toJson(group), MasterDnsGroup::class.java))
        assertFalse(encoded.contains("synthetic"))
    }

    @Test fun onlyTwoToEightDistinctServersFromOneSourceAreAccepted() {
        assertEquals(listOf("a", "b"), group.members(listOf(profile("a"), profile("b"))).map { it.id })
        for (bad in listOf(group.copy(memberIds = listOf("a")), group.copy(memberIds = listOf("a", "a")),
            group.copy(memberIds = (1..9).map { "$it" }), group.copy(strategy = "unknown"), group.copy(name = ""))) {
            assertTrue(runCatching { bad.validate() }.isFailure)
        }
        for (profiles in listOf(listOf(profile("a")), listOf(profile("a"), profile("b", "s")),
            listOf(profile("a", "s"), profile("b", "s")), listOf(profile("a"), Profile("b", "VPN")))) {
            assertTrue(runCatching { group.members(profiles) }.isFailure)
        }
        assertEquals(2, group.copy(subscriptionId = "s").members(listOf(profile("a", "s"), profile("b", "s"))).size)
    }

    @Test fun legacyMigrationMovesOnlyMembershipAndIsIdempotent() {
        val a = profile("a")
        val cfg = (a.kernelConfig as KernelConfig.MasterDns).config.copy(
            localPool = MasterDnsPoolSelection(listOf("a", "b")), localResolvers = "198.51.100.53", useLocalResolvers = true)
        val profiles = listOf(a.copy(kernelConfig = KernelConfig.MasterDns(cfg)), profile("b"))
        val rawProfiles = JsonParser.parseString(gson.toJson(profiles)).asJsonArray
        rawProfiles[1].asJsonObject.addProperty("futureField", "preserved")
        val p = mutablePreferencesOf(AppPreferences.CURRENT_PROFILE_ID to "a",
            AppPreferences.PROFILES_JSON to rawProfiles.toString(),
            AppPreferences.ACTIVE_KERNEL_JSON to gson.toJson(KernelSnapshot(variant = "MASTERDNS", masterdns = cfg)))
        MasterDnsGroups.migrate(p, profiles, gson)
        val migrated = MasterDnsGroups.read(p, gson).single()
        assertEquals(listOf("a", "b"), migrated.memberIds)
        assertEquals(migrated.id, p[MasterDnsGroups.activeKey])
        val decoded = decodeStoredProfiles(p[AppPreferences.PROFILES_JSON]!!, gson, "DNS")
        assertEquals(cfg.copy(localPool = null), (decoded.first().kernelConfig as KernelConfig.MasterDns).config)
        assertNull((decodeStoredKernelSnapshot(p[AppPreferences.ACTIVE_KERNEL_JSON], gson) as KernelConfig.MasterDns).config.localPool)
        assertEquals("preserved", JsonParser.parseString(p[AppPreferences.PROFILES_JSON]).asJsonArray[1]
            .asJsonObject["futureField"].asString)
        val before = p.toPreferences()
        MasterDnsGroups.migrate(p, decoded, gson)
        assertEquals(before, p.toPreferences())
    }

    @Test fun migrationRetainsUnavailableMemberForVisibleRepair() {
        val a = profile("a")
        val legacy = a.copy(kernelConfig = KernelConfig.MasterDns(
            (a.kernelConfig as KernelConfig.MasterDns).config.copy(localPool = group.selection())))
        val p = mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(listOf(legacy)))
        MasterDnsGroups.migrate(p, listOf(legacy), gson)
        assertEquals(listOf("a", "b"), MasterDnsGroups.read(p, gson).single().memberIds)
        assertNull(p[MasterDnsGroups.activeKey])
    }

    @Test fun realSubscriptionCommitPreservesGroupEvenWhenMemberDisappears() {
        val members = listOf(profile("a", "s"), profile("b", "s"))
        val sub = Subscription("s", "Fixture", "https://example.invalid/sub")
        val subscribedGroup = group.copy(subscriptionId = "s")
        val p = mutablePreferencesOf(AppPreferences.CURRENT_PROFILE_ID to "a",
            AppPreferences.PROFILES_JSON to gson.toJson(members),
            AppPreferences.SUBSCRIPTIONS_JSON to gson.toJson(listOf(sub)),
            MasterDnsGroups.groupsKey to gson.toJson(listOf(subscribedGroup)),
            MasterDnsGroups.activeKey to group.id)
        val before = SubscriptionImportSnapshot(members, listOf(sub), "a",
            p[AppPreferences.PROFILES_JSON]!!, p[AppPreferences.SUBSCRIPTIONS_JSON]!!)
        val updated = members.first().copy(name = "Refreshed")
        SubscriptionImportCommit.write(p, before, listOf(updated), listOf(sub), null, gson)
        assertEquals(listOf(subscribedGroup), MasterDnsGroups.read(p, gson))
        assertEquals(group.id, p[MasterDnsGroups.activeKey])
        val after = decodeStoredProfiles(p[AppPreferences.PROFILES_JSON]!!, gson, "DNS")
        assertTrue(runCatching { subscribedGroup.members(after) }.isFailure)
        assertTrue(after.none { it.id == group.id })
    }
}
