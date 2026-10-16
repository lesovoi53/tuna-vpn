package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.wireturn.app.domain.SubscriptionGroups
import com.wireturn.app.domain.SubscriptionGroupMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAdversarialTest {
    private val gson = Gson()
    private val members = listOf(
        Profile(id = "local-a", name = "A", kernelConfig = KernelConfig.Vpn(),
            subscriptionId = "sub", subscriptionSourceId = "a"),
        Profile(id = "local-b", name = "B", kernelConfig = KernelConfig.Vpn(),
            subscriptionId = "sub", subscriptionSourceId = "b"),
    )

    private fun document() = JsonParser.parseString("""{
        "groups":[{"id":"remote-group","name":"Remote","category":"VPN","type":"URL_TEST",
        "memberIds":["a","b"],"testUrl":"https://example.invalid/probe",
        "selectionMode":"PRIORITY","family":"VPN","probeMethod":"GET"}]
    }""").asJsonObject

    @Test fun newRemoteGroupCannotOverwriteAnUnownedLocalGroup() {
        val doc = document()
        val local = SubscriptionGroups.parse(doc, "sub", members).single().copy(name = "My local group")
        val old = Subscription("sub", "Demo", "https://example.invalid/sub", tunaDocument = "{\"groups\":[]}")
        val incoming = old.copy(tunaDocument = doc.toString())
        val groupsKey = stringPreferencesKey("connection_groups_v1")
        val preferences = mutablePreferencesOf(
            AppPreferences.PROFILES_JSON to gson.toJson(members),
            AppPreferences.SUBSCRIPTIONS_JSON to gson.toJson(listOf(old)),
            groupsKey to gson.toJson(listOf(local)),
        )
        val snapshot = SubscriptionImportSnapshot(members, listOf(old), "",
            preferences[AppPreferences.PROFILES_JSON]!!, preferences[AppPreferences.SUBSCRIPTIONS_JSON]!!)
        val before = preferences.toPreferences()

        val result = runCatching {
            SubscriptionImportCommit.write(preferences, snapshot, members, listOf(incoming), null, gson)
        }

        assertTrue("An unowned group ID collision must reject the import", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Rejected import must retain every preference", before, preferences.toPreferences())
    }

    @Test fun ambiguousSourceIdentityCannotSilentlySelectAnotherEndpoint() {
        val ambiguous = members + members.first().copy(id = "shadow-a", name = "Other endpoint")
        val result = runCatching { SubscriptionGroups.parse(document(), "sub", ambiguous) }

        assertTrue("Two profiles with source ID a must not resolve by list order",
            result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun optionalEnumFieldsRejectSingleElementArrays() {
        assertEquals(1, SubscriptionGroups.parse(document(), "sub", members).size)
        for (field in listOf("selectionMode", "family", "probeMethod")) {
            val doc = document()
            val group = doc.getAsJsonArray("groups")[0].asJsonObject
            group.add(field, JsonArray().apply { add(group[field].deepCopy()) })

            val result = runCatching { SubscriptionGroups.parse(doc, "sub", members) }

            assertTrue("$field must be a string, not a single-element array",
                result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun refreshRestoresLocallyDeletedMemberWithoutLosingGroupEdits() {
        val doc = document()
        doc.getAsJsonArray("groups")[0].asJsonObject.addProperty("routingProfileId", "b")
        val old = Subscription("sub", "Demo", "https://example.invalid/sub", tunaDocument = doc.toString())
        val local = SubscriptionGroups.parse(doc, "sub", members).single().copy(name = "My group name")
        val afterLocalDeletion = members.filterNot { it.subscriptionSourceId == "b" }
        // A removed subscription endpoint is imported with a fresh local ID on refresh.
        val restored = members.map { if (it.subscriptionSourceId == "b") it.copy(id = "restored-b") else it }

        val updated = SubscriptionGroupMerge.merge(listOf(local), listOf(old), listOf(old),
            afterLocalDeletion, restored).single()

        assertEquals("My group name", updated.name)
        assertEquals(listOf("local-a", "restored-b"), updated.memberIds)
        assertEquals("restored-b", updated.routingProfileId)
        updated.validate(restored)
    }

    @Test fun deletingSubscriptionStillRemovesOwnedGroupAfterMemberWasDeleted() {
        val doc = document()
        val old = Subscription("sub", "Demo", "https://example.invalid/sub", tunaDocument = doc.toString())
        val owned = SubscriptionGroups.parse(doc, "sub", members).single()
        val unrelated = owned.copy(id = "independent-group")

        val result = SubscriptionGroupMerge.merge(listOf(owned, unrelated), listOf(old), emptyList(),
            members.take(1), emptyList())

        assertEquals(listOf(unrelated), result)
    }

    @Test fun unrelatedSubscriptionUpdateDoesNotReparseIncompleteUnchangedGroup() {
        val doc = document()
        val sub = Subscription("sub", "Demo", "https://example.invalid/sub", tunaDocument = doc.toString())
        val group = SubscriptionGroups.parse(doc, "sub", members).single()
        val other = Subscription("other", "Other", "https://example.invalid/other")
        val surviving = members.take(1)
        val updated = surviving + members.last().copy(id = "other-profile", subscriptionId = "other")

        val result = SubscriptionGroupMerge.merge(listOf(group), listOf(sub), listOf(sub, other), surviving, updated)

        assertEquals(listOf(group), result)
    }

    @Test fun historicalDocumentResolvesRemovedEndpointWithoutRestoringItToSavedProfiles() {
        val doc = document().apply {
            add("profiles", JsonArray().apply {
                for (id in listOf("a", "b", "c")) add(JsonParser.parseString("""{
                    "id":"$id","name":"$id","uri":"snell://secret@$id.example.invalid:443?version=4"
                }"""))
            })
        }
        val old = Subscription("sub", "Demo", "https://example.invalid/sub", tunaDocument = doc.toString())
        val local = SubscriptionGroups.parse(doc, "sub", members).single().copy(name = "My name")
        val surviving = listOf(members.first(), members.last().copy(id = "local-c", subscriptionSourceId = "c"))
        val nextDoc = doc.deepCopy().apply {
            getAsJsonArray("groups")[0].asJsonObject.add("memberIds", JsonArray().apply { add("a"); add("c") })
        }

        val updated = SubscriptionGroupMerge.merge(listOf(local), listOf(old),
            listOf(old.copy(tunaDocument = nextDoc.toString())), surviving, surviving).single()

        assertEquals("My name", updated.name)
        assertEquals(listOf("local-a", "local-c"), updated.memberIds)
        assertEquals(listOf("local-a", "local-c"), surviving.map { it.id })
        updated.validate(surviving)
    }

    @Test fun unreferencedInvalidHistoricalEndpointDoesNotBlockGroupRefresh() {
        val doc = document().apply {
            add("profiles", JsonParser.parseString("""[
                {"id":"unused","name":"Unused","uri":"unsupported://example.invalid"}
            ]"""))
        }
        val old = Subscription("sub", "Demo", "https://example.invalid/sub", tunaDocument = doc.toString())
        val local = SubscriptionGroups.parse(doc, "sub", members).single().copy(name = "My name")
        val nextDoc = doc.deepCopy().apply {
            getAsJsonArray("groups")[0].asJsonObject.addProperty("name", "Remote rename")
        }

        val updated = SubscriptionGroupMerge.merge(listOf(local), listOf(old),
            listOf(old.copy(tunaDocument = nextDoc.toString())), members, members).single()

        assertEquals("My name", updated.name)
        assertEquals(local.memberIds, updated.memberIds)
    }
}
