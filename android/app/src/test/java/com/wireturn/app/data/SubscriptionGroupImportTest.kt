package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.domain.SubscriptionGroups
import com.wireturn.app.domain.SubscriptionGroupMerge
import org.junit.Assert.*
import org.junit.Test

class SubscriptionGroupImportTest {
    @Test fun storedSpeedGroupsKeepTheirTypeAndCannotBecomeActiveUrlGroups() {
        val members = profiles()
        val legacy = ConnectionGroup("old", "Saved speed group", ConnectionCategory.VPN, GroupTestType.SPEEDTEST,
            members.map { it.id }, automatic = true, testOnConnect = true, testUrl = "https://example.invalid/old-file")
        val restored = decodeStoredGroups(Gson().toJson(listOf(legacy)), members).single()
        assertEquals(GroupTestType.SPEEDTEST, restored.type)
        assertEquals(legacy.name, restored.name)
        assertEquals(legacy.testUrl, restored.testUrl)
        assertEquals(legacy.memberIds, restored.memberIds)
        assertFalse(com.wireturn.app.domain.ManualGroupPolicy.allows(restored))
        assertEquals(restored.id, com.wireturn.app.domain.ManualGroupPolicy.activeGroupToClear(restored.id, listOf(restored)))
        assertTrue(runCatching { restored.validateForTesting(members) }.isFailure)
    }

    @Test fun legacySpeedSubscriptionGroupIsSkippedWithoutResolvingItsMembers() {
        val raw = """{"groups":[{"id":"legacy","type":"SPEEDTEST","memberIds":["missing"],"automatic":true}]}"""
        assertTrue(SubscriptionGroups.parse(JsonParser.parseString(raw).asJsonObject, "sub", profiles()).isEmpty())
    }

    @Test fun refreshCannotReactivateLocalAutomaticFlagsWhenRemoteAnchorIsLost() {
        val members = profiles()
        val old = sub(document().replace("\"automatic\":true", "\"automatic\":false")
            .replace("\"testOnConnect\":true", "\"testOnConnect\":false"))
        val local = SubscriptionGroupMerge.merge(emptyList(), emptyList(), listOf(old), emptyList(), members)
            .single().copy(automatic = true, testOnConnect = true)
        val broken = JsonParser.parseString(old.tunaDocument).asJsonObject
        val group = broken.getAsJsonArray("groups")[0].asJsonObject
        group.add("memberIds", JsonParser.parseString("[\"a\"]"))
        group.addProperty("routingProfileId", "")
        group.addProperty("repairRequired", true)
        val next = sub(broken.toString())
        val updated = SubscriptionGroupMerge.merge(listOf(local), listOf(old), listOf(next), members, members.take(1)).single()
        assertTrue(updated.repairRequired)
        assertFalse("Repair must override edited automatic flag", updated.automatic)
        assertFalse("Repair must override edited test-on-connect flag", updated.testOnConnect)
        assertEquals("", updated.routingProfileId)
        assertTrue(runCatching { updated.validateForTesting(members.take(1)) }.isFailure)
    }

    @Test fun rotatingAnOrdinarySourceIdDoesNotDuplicateHistoricalLocalIdentity() {
        val doc = sub(document())
        val old = profiles() + Profile(id="ordinary-local", name="Ordinary", subscriptionId="sub",
            subscriptionSourceId="uri-hash-before", kernelConfig=KernelConfig.Vpn())
        val next = old.map { if (it.id == "ordinary-local") it.copy(subscriptionSourceId="uri-hash-after") else it }
        val groups = SubscriptionGroupMerge.merge(emptyList(),emptyList(),listOf(doc),emptyList(),old)
        val merged = SubscriptionGroupMerge.merge(groups,listOf(doc),listOf(doc),old,next)
        assertEquals(groups,merged)
        assertEquals(merged,SubscriptionGroupMerge.merge(merged,listOf(doc),listOf(doc),next,next))
    }
    @Test fun demoSubscriptionImportsEveryProtocolAndSkipsRetiredSpeedGroups() {
        val text = javaClass.getResource("/group-subscription-demo.json")!!.readText()
        val doc = com.wireturn.app.domain.TunaSubscriptionDocument.parse(text, setOf(SubscriptionGroups.CAPABILITY))
        val members = doc.profiles.map { p ->
            val decoded = com.wireturn.app.kernel.KernelRegistry.decodeUri(p["uri"].asString)
            assertNotNull(p["id"].asString, decoded)
            Profile(id="local-" + p["id"].asString, name=p["name"].asString,
                kernelConfig=decoded!!.second, subscriptionId="sub", subscriptionSourceId=
                    p["id"].asString)
        }
        val groups = SubscriptionGroups.parse(doc.source,"sub",members)
        assertEquals(12,members.size); assertEquals(3,groups.size)
        assertEquals(GroupFamily.entries.toSet(),groups.map { it.family }.toSet())
        assertEquals(setOf(GroupTestType.URL_TEST),groups.map { it.type }.toSet())
        assertTrue(groups.none { it.automatic || it.testOnConnect })
    }
    private val gson = Gson()
    private fun profiles(a: KernelConfig = KernelConfig.Vpn(), b: KernelConfig = a) = listOf(
        Profile(id="local-a", name="A", subscriptionId="sub", subscriptionSourceId="a", kernelConfig=a),
        Profile(id="local-b", name="B", subscriptionId="sub", subscriptionSourceId="b", kernelConfig=b))
    private fun document(category: String="VPN", family: String="VPN", name: String="Remote", interval: Int=300) =
        """{"groups":[{"id":"g","name":"$name","category":"$category","family":"$family","type":"URL_TEST",
        "memberIds":["a","b"],"testUrl":"https://example.invalid/test","expectedStatus":200,
        "intervalSeconds":$interval,"automatic":true,"selectionMode":"PRIORITY","probeMethod":"GET",
        "holdSeconds":150,"confirmations":3,"improvementPercent":25,"maxBytesPerCandidate":2048,
        "durationSeconds":7,"allowMobile":false,"testOnConnect":true,"freshnessSeconds":900,"routingProfileId":"b"}]}"""
    private fun sub(doc: String) = Subscription("sub","Demo","https://example.invalid/sub",tunaDocument=doc)
    @Test fun allFamiliesImportAndCrossFamilyMixIsRejected() {
        val cases = listOf(Triple("VPN", "VPN", profiles()),
            Triple("BYPASS", "QWDTT", profiles(KernelConfig.Qwdtt())),
            Triple("BYPASS", "WEBDAV", profiles(KernelConfig.Webdav())))
        for ((category,family,members) in cases) {
            val group=SubscriptionGroups.parse(JsonParser.parseString(document(category,family)).asJsonObject,"sub",members).single()
            assertEquals(GroupFamily.valueOf(family),group.family)
            assertEquals(GroupSelectionMode.PRIORITY,group.effectiveSelectionMode)
            assertEquals(150,group.holdSeconds); assertEquals(3,group.confirmations)
            assertEquals(2048L,group.maxBytesPerCandidate); assertEquals(7,group.durationSeconds)
            assertEquals("local-b",group.routingProfileId); assertTrue(group.automatic)
        }
        assertTrue(runCatching { SubscriptionGroups.parse(JsonParser.parseString(document("BYPASS","WEBDAV")).asJsonObject,
            "sub",profiles(KernelConfig.Webdav(),KernelConfig.Qwdtt())) }.isFailure)
        assertTrue(runCatching { SubscriptionGroups.parse(JsonParser.parseString(document("BYPASS","WEBDAV")).asJsonObject,
            "sub",profiles(KernelConfig.MasterDns())) }.isFailure)
    }
    @Test fun refreshIsIdempotentPreservesLocalEditsAndRemovesOnlyOwnedGroups() {
        val members=profiles(); val old=sub(document())
        val first=SubscriptionGroupMerge.merge(emptyList(),emptyList(),listOf(old),emptyList(),members)
        assertEquals(first,SubscriptionGroupMerge.merge(first,listOf(old),listOf(old),members,members))
        val local=first.single().copy(name="My name",automatic=false)
        val unrelated=local.copy(id="unrelated")
        val next=sub(document(name="Server rename",interval=600))
        val result=SubscriptionGroupMerge.merge(listOf(local,unrelated),listOf(old),listOf(next),members,members)
        val updated=result.first { it.id==local.id }
        assertEquals("My name",updated.name); assertFalse(updated.automatic); assertEquals(600,updated.intervalSeconds)
        assertEquals(result,SubscriptionGroupMerge.merge(result,listOf(next),listOf(sub("""{"groups":[]}""")),members,members))
    }
    @Test fun invalidGroupRejectsWholeCommitAndValidGroupsCommitTogether() {
        val p=mutablePreferencesOf(AppPreferences.PROFILES_JSON to "[]",AppPreferences.SUBSCRIPTIONS_JSON to "[]")
        val snapshot=SubscriptionImportSnapshot(emptyList(),emptyList(),"", "[]","[]")
        val before=p.toPreferences()
        val bad=sub(document().replace("\"b\"]","\"missing\"]"))
        assertTrue(runCatching { SubscriptionImportCommit.write(p,snapshot,profiles(),listOf(bad),null,gson) }.isFailure)
        assertEquals(before,p.toPreferences())
        SubscriptionImportCommit.write(p,snapshot,profiles(),listOf(sub(document())),null,gson)
        val groups=gson.fromJson(p[stringPreferencesKey("connection_groups_v1")],Array<ConnectionGroup>::class.java)
        assertEquals(1,groups.size); assertEquals(listOf("local-a","local-b"),groups.single().memberIds)
        assertNotEquals("[]",p[AppPreferences.PROFILES_JSON])
    }

    @Test fun removingSubscriptionRemovesOnlyOwnedGroupsAtomically() {
        val members=profiles();val old=sub(document())
        val owned=SubscriptionGroupMerge.merge(emptyList(),emptyList(),listOf(old),emptyList(),members).single()
        val unrelated=owned.copy(id="local-independent")
        val p=mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(members),
            AppPreferences.SUBSCRIPTIONS_JSON to gson.toJson(listOf(old)),
            stringPreferencesKey("connection_groups_v1") to gson.toJson(listOf(owned,unrelated)))
        val snap=SubscriptionImportSnapshot(members,listOf(old),"",p[AppPreferences.PROFILES_JSON]!!,p[AppPreferences.SUBSCRIPTIONS_JSON]!!)
        SubscriptionImportCommit.write(p,snap,emptyList(),emptyList(),null,gson)
        val remaining=gson.fromJson(p[stringPreferencesKey("connection_groups_v1")],Array<ConnectionGroup>::class.java)
        assertEquals(listOf("local-independent"),remaining.map { it.id })
    }
    @Test fun localDefaultSelectionModeIsPreservedOnRefresh() {
        val members=profiles();val old=sub(document())
        val local=SubscriptionGroupMerge.merge(emptyList(),emptyList(),listOf(old),emptyList(),members).single().copy(selectionMode=null)
        val merged=SubscriptionGroupMerge.merge(listOf(local),listOf(old),listOf(sub(document(interval=600))),members,members).single()
        assertEquals(GroupSelectionMode.BEST,merged.effectiveSelectionMode)
        assertEquals(600,merged.intervalSeconds)
    }
    @Test fun routingProfileIdMustBeAStringNotANumber() {
        val members=profiles().mapIndexed { i,p -> p.copy(subscriptionSourceId=(i+1).toString()) }
        val raw=document().replace("\"a\"","\"1\"").replace("\"b\"","\"2\"")
            .replace("\"routingProfileId\":\"2\"","\"routingProfileId\":2")
        assertTrue(runCatching { SubscriptionGroups.parse(JsonParser.parseString(raw).asJsonObject,"sub",members) }.isFailure)
    }
}
