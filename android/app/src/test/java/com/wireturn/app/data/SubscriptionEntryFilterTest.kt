package com.wireturn.app.data

import com.google.gson.JsonParser
import com.wireturn.app.domain.SubscriptionEntryFilter
import org.junit.Assert.*
import org.junit.Test

class SubscriptionEntryFilterTest {
    @Test fun naiveAndValidPeersSurviveMixedSubscriptionImport() {
        val naive = "naive+https://test:password@example.com:443#Naive"
        assertTrue(SubscriptionEntryFilter.supported(naive))
        val document = JsonParser.parseString("""{"profiles":[{"id":"naive","uri":"$naive"},
            {"id":"bad","uri":"unsupported://invalid"},{"id":"b","uri":"$naive"}],
            "groups":[{"memberIds":["naive","bad","b"]}]}""").asJsonObject
        val (filtered, skipped) = SubscriptionEntryFilter.filterDocument(document)
        assertEquals(1, skipped)
        assertEquals(listOf("naive", "b"), filtered.getAsJsonArray("groups")[0].asJsonObject.getAsJsonArray("memberIds").map { it.asString })
        assertEquals(2, filtered.getAsJsonArray("profiles").size())
    }
    @Test fun invalidEndpointDoesNotDiscardValidProfilesOrLeaveDanglingGroupMembers() {
        val uri="vless://11111111-2222-4333-8444-555555555555@192.0.2.1:443?security=tls"
        val doc=JsonParser.parseString("""{"profiles":[{"id":"a","uri":"$uri"},{"id":"b","uri":"$uri"},
            {"id":"bad","uri":"unsupported://example.invalid"}],"groups":[
            {"memberIds":["a","bad","b"],"routingProfileId":"bad"},{"memberIds":["bad","a"]}]}""").asJsonObject
        val (filtered,skipped)=SubscriptionEntryFilter.filterDocument(doc)
        assertEquals(1,skipped); assertEquals(2,filtered.getAsJsonArray("profiles").size())
        val groups=filtered.getAsJsonArray("groups");assertEquals(2,groups.size())
        assertEquals(listOf("a","b"),groups[0].asJsonObject.getAsJsonArray("memberIds").map { it.asString })
        assertEquals("",groups[0].asJsonObject["routingProfileId"].asString)
        assertTrue(groups.all { it.asJsonObject["repairRequired"].asBoolean })
        assertTrue(groups.none { it.asJsonObject["automatic"].asBoolean })
        assertEquals(3,doc.getAsJsonArray("profiles").size())
    }
    @Test fun privateLiveSubscriptionRegistryCheck() {
        val path=System.getenv("TUNA_LIVE_SUBSCRIPTION_FILE") ?: return
        val lines=java.io.File(path).readLines().filter(String::isNotBlank)
        val accepted=lines.filter(SubscriptionEntryFilter::supported)
        assertTrue("Expected some valid entries",accepted.isNotEmpty())
        for (scheme in listOf("qwdtt", "webdav")) {
            assertTrue("Missing supported $scheme",accepted.any { it.startsWith("$scheme://") })
        }
        println("Live subscription: accepted=${accepted.size}, skipped=${lines.size-accepted.size}")
    }
}
