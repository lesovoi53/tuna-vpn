package com.wireturn.app.data

import com.google.gson.JsonParser
import com.wireturn.app.domain.SubscriptionEntryFilter
import com.wireturn.app.domain.SubscriptionGroups
import org.junit.Assert.*
import org.junit.Test

class GroupRepairImportTest {
    @Test fun rejectedRoutingAnchorPreservesGroupWithoutSilentlySelectingAnotherRoute() {
        val uri = "naive+https://fixture:password@example.invalid:443"
        val document = JsonParser.parseString("""{"profiles":[{"id":"a","uri":"$uri"},{"id":"b","uri":"$uri"},{"id":"bad","uri":"unsupported://example.invalid"}],
            "groups":[{"id":"g","name":"Recoverable","category":"VPN","type":"URL_TEST","memberIds":["a","bad","b"],
            "testUrl":"https://example.invalid/generate_204","routingProfileId":"bad","automatic":true}]}""").asJsonObject
        val profiles = listOf("a", "b").map { Profile(it, it, KernelConfig.Vpn(), subscriptionId="sub", subscriptionSourceId=it) }
        val filtered = SubscriptionEntryFilter.filterDocument(document).first
        val group = SubscriptionGroups.parse(filtered, "sub", profiles).single()
        assertEquals(listOf("a", "b"), group.memberIds)
        assertEquals("", group.routingProfileId)
        assertTrue(group.repairRequired)
        assertFalse(group.automatic)
        assertTrue(runCatching { group.validateForTesting(profiles) }.isFailure)
        assertEquals("bad", document.getAsJsonArray("groups")[0].asJsonObject["routingProfileId"].asString)
        assertEquals(group, SubscriptionGroups.parse(filtered, "sub", profiles).single())
    }
}
