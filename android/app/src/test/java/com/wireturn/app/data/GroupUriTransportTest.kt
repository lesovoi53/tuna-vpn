package com.wireturn.app.data

import com.wireturn.app.domain.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class GroupUriTransportTest {
    @Test fun rejectedEndpointInsideContainerDoesNotDiscardOtherProfiles() {
        val source = com.google.gson.JsonParser.parseString(fixture()).asJsonObject
        source.getAsJsonArray("profiles").add(com.google.gson.JsonObject().apply {
            addProperty("id", "unsupported-extra"); addProperty("name", "Unsupported")
            addProperty("uri", "unsupported://example.invalid")
        })
        val envelope = GroupUriTransport.extract(uri(source.toString()))!!
        val (filtered, count) = SubscriptionEntryFilter.filterDocument(envelope.document.source)
        assertTrue(count >= 1)
        val accepted = envelope.copy(document = TunaSubscriptionDocument.parse(filtered.toString(), GroupUriTransport.capabilities))
        val profiles = GroupUriTransport.mergeProfiles(accepted, emptyList())
        assertTrue(profiles.isNotEmpty())
        assertFalse(profiles.any { it.id == "unsupported-extra" })
    }
    private fun uri(text: String) = "tuna-groups://v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun fixture() = javaClass.getResource("/group-subscription-demo.json")!!.readText()
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun rawAndBase64ListsPreserveDocumentAndPanelFragment() {
        val source = fixture()
        val body = "vless://example.invalid#ordinary\n" + uri(source) + "#renamed%20by%20panel\n"
        for (text in listOf(body, Base64.getEncoder().encodeToString(body.toByteArray()))) {
            val parsed = GroupUriTransport.extract(text)!!
            assertEquals(12,parsed.document.profiles.size)
            assertEquals(6,parsed.document.source.getAsJsonArray("groups").size())
            assertTrue(parsed.ordinaryText.contains("#ordinary"))
            assertEquals(TunaSubscriptionDocument.parse(source,GroupUriTransport.capabilities).source,parsed.document.source)
        }
    }
    @Test fun versionsAlphabetCanonicalEncodingAndStrictJson() {
        val good=uri(fixture())
        listOf(good.replace("://v1/","://v2/"),good+"=",good+"?x=1",good+"%20",
            uri("\uFEFF"+fixture()),uri(fixture()+"{}"),uri("{unquoted:true}"),
            uri(fixture().replaceFirst("{","{\"duplicate\":1,\"duplicate\":2,")),
            "tuna-groups://v1/_w", "tuna-groups://v1/" + "A".repeat(3*1024*1024)
        ).forEach { bad -> fails { GroupUriTransport.extract(bad) } }
    }
    @Test fun duplicatesAreIdempotentButConflictingSnapshotsReject() {
        val good=uri(fixture())
        assertNotNull(GroupUriTransport.extract("$good\n$good#other"))
        val d=GroupUriTransport.decode(good).source.deepCopy().apply { addProperty("revision",999999) }
        fails { GroupUriTransport.extract("$good\n"+uri(d.toString())) }
    }
    @Test fun completeEmptySnapshotIsPreserved() {
        val doc=GroupUriTransport.decode(uri(fixture())).source.deepCopy().apply {
            add("profiles",com.google.gson.JsonArray());add("groups",com.google.gson.JsonArray())
        }
        val result=GroupUriTransport.extract(uri(doc.toString()))!!
        assertTrue(result.document.profiles.isEmpty())
        assertEquals(0,result.document.source.getAsJsonArray("groups").size())
    }
    @Test fun ordinarySubscriptionsAreNotChanged() {
        assertNull(GroupUriTransport.extract("vless://example.invalid"))
        assertNull(GroupUriTransport.extract(fixture()))
    }
    @Test fun mergedProfilesPreferContainerIdsAndPreserveUnrelatedServers() {
        val doc=GroupUriTransport.decode(uri(fixture()))
        val link=doc.profiles.first()["uri"].asString.substringBefore('#')+"#panel-name"
        val duplicate=Profile(id=java.util.UUID.nameUUIDFromBytes(link.toByteArray()).toString(),name="old")
        val unrelated=Profile(id="ordinary",name="keep")
        val merged=GroupUriTransport.mergeProfiles(GroupUriTransport.Envelope(doc,link),listOf(duplicate,unrelated))
        assertEquals(doc.profiles.size+1,merged.size)
        assertEquals(doc.profiles.first()["id"].asString,merged.first().id)
        assertEquals(unrelated,merged.last())
    }
    @Test fun privateLiveContainerChecksGroupReferences() {
        val path=System.getenv("TUNA_LIVE_SUBSCRIPTION_FILE") ?: return
        val doc=GroupUriTransport.extract(java.io.File(path).readText())?.document ?: return
        val members=GroupUriTransport.mergeProfiles(GroupUriTransport.Envelope(doc,""),emptyList()).map {
            it.copy(subscriptionId="live",subscriptionSourceId=it.id)
        }
        val groups=SubscriptionGroups.parse(doc.source,"live",members)
        assertTrue(groups.isNotEmpty())
        println("Live container verified: profiles=${members.size}, groups=${groups.size}")
    }

    @Test fun malformedGroupJsonIsAFormatErrorRatherThanNetworkFailure() {
        val failure=runCatching { GroupUriTransport.decode(uri("{\"schema\":")) }.exceptionOrNull()
        assertTrue("fetchSubscription maps IllegalArgumentException to InvalidFormat",failure is IllegalArgumentException)
    }
}
