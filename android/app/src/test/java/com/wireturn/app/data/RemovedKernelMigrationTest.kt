package com.wireturn.app.data

import com.google.gson.GsonBuilder
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class RemovedKernelMigrationTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val vpn = Profile("vpn", "VPN", KernelConfig.Vpn(VpnConfig("snell://secret@192.0.2.1:443?version=5")))
    private val qwdtt = Profile("q", "qWDTT", KernelConfig.Qwdtt(QwdttConfig(peer = "192.0.2.2:56000", password = "test", vkHashes = "abcdefghijklmnop1234")))

    @Test fun removedRecordsCannotDiscardOrChangeSupportedProfiles() {
        val retired = listOf("obsolete_a", "obsolete_b").map { type ->
            """{"id":"$type","name":"Retired","kernelConfig":{"type":"$type","config":{}}}"""
        }
        val source = "[" + (listOf(gson.toJson(vpn)) + retired + gson.toJson(qwdtt)).joinToString(",") + "]"
        val loaded = decodeStoredProfiles(source, gson, "Profile")
        assertEquals(listOf(vpn.sanitize(), qwdtt.sanitize()), loaded)
        assertFalse(loaded.any { it.id in setOf("obsolete_a", "obsolete_b") })
    }

    @Test fun removedActiveSelectionNeverUsesLeftoverVpnFields() {
        for (type in listOf("OBSOLETE_A", "OBSOLETE_B", "FUTURE")) {
            val source = """{"variant":"$type","vpn":${gson.toJson((vpn.kernelConfig as KernelConfig.Vpn).config)}}"""
            val loaded = decodeStoredKernelSnapshot(source, gson)
            assertEquals(KernelConfig.Vpn(), loaded)
            assertFalse(ClientConfig(kernelConfig = loaded).fillDefaults().isValid)
        }
    }

    @Test fun oldMixedGroupsAreRepairedWithoutLosingValidGroups() {
        val mixed = ConnectionGroup("mixed", "Mixed", ConnectionCategory.BYPASS, GroupTestType.URL_TEST,
            listOf("obsolete_a", "q"), routingProfileId = "obsolete_a", automatic = true, testOnConnect = true)
        val valid = ConnectionGroup("valid", "Valid", ConnectionCategory.VPN, GroupTestType.URL_TEST,
            listOf("vpn", "vpn2"), routingProfileId = "vpn", family = GroupFamily.VPN)
        val retired = mixed.copy(id = "retired", memberIds = listOf("obsolete_b"), routingProfileId = "obsolete_b")
        val malformed = """{"id":"broken","memberIds":null}"""
        val mixedJson = gson.toJson(mixed).dropLast(1) + """, "family":"OBSOLETE_FAMILY"}"""
        val source = "[$mixedJson,$malformed,${gson.toJson(valid)},${gson.toJson(retired)}]"
        val profiles = listOf(vpn, vpn.copy(id = "vpn2"), qwdtt)
        val loaded = decodeStoredGroups(source, profiles)
        assertEquals(listOf("mixed", "valid", "retired"), loaded.map { it.id })
        assertEquals(valid, loaded[1])
        assertEquals(GroupFamily.QWDTT, loaded[0].family)
        for (group in listOf(loaded[0], loaded[2])) {
            assertTrue(group.repairRequired)
            assertFalse(group.automatic)
            assertFalse(group.testOnConnect)
            assertEquals("", group.routingProfileId)
            assertThrows(IllegalArgumentException::class.java) { group.validateForTesting(profiles) }
        }
    }

    @Test fun structuredSubscriptionWithRetiredHistoryCanRefreshItsSurvivingGroup() {
        val uri = qwdtt.qwdttConfig.toUri()
        val oldDocument = """{"profiles":[
            {"id":"q","name":"qWDTT","uri":"$uri"},
            {"id":"removed","name":"Retired","uri":"unknown-transport://example.invalid"}],
            "groups":[{"id":"g","name":"Old","category":"BYPASS","family":"OBSOLETE_FAMILY","type":"URL_TEST",
            "memberIds":["q","removed"],"routingProfileId":"q","testUrl":"https://example.invalid/test"}]}"""
        val previous = Subscription("s", "S", "https://example.invalid/sub", tunaDocument = oldDocument)
        val profile = qwdtt.copy(subscriptionId = "s", subscriptionSourceId = "q")
        val filtered = com.wireturn.app.domain.SubscriptionEntryFilter.filterDocument(
            com.google.gson.JsonParser.parseString(oldDocument).asJsonObject).first
        val next = previous.copy(tunaDocument = filtered.toString())
        val groups = com.wireturn.app.domain.SubscriptionGroupMerge.merge(emptyList(), listOf(previous),
            listOf(next), listOf(profile), listOf(profile))
        assertEquals(1, groups.size)
        assertEquals(listOf("q"), groups.single().memberIds)
        assertEquals(GroupFamily.QWDTT, groups.single().family)
        assertTrue(groups.single().repairRequired)
        assertFalse(groups.single().automatic)
    }
}
