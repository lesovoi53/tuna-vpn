package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SafeEnumTypeAdapterFactory
import com.wireturn.app.data.kernel.VpnConfig
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LegacySubscriptionIdentityTest {
    private val gson = GsonBuilder().registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val uri = "snell://fixture-password@vpn.example.invalid:443?version=5&reuse=true"

    private fun json(name: String, endpoint: String = uri, id: String? = null) = JsonObject().apply {
        addProperty("name", name)
        addProperty("uri", endpoint)
        id?.let { addProperty("id", it) }
    }.toString()

    private fun parse(json: String, wrapped: Boolean = false): Profile {
        if (!wrapped) return LegacyProfileImport.parse(json, gson).profiles.single()
        val link = "tuna://" + ProfileEncoder.encode(json)
        return LegacyProfileImport.parse(ProfileEncoder.decode(link.substringAfter("://"))!!, gson,
            missingId = { index, total -> UUID.nameUUIDFromBytes(
                (if (total == 1) link else "$link#$index").toByteArray(Charsets.UTF_8)).toString() }).profiles.single()
    }

    @Test fun unchangedIdlessJsonRetainsItsLocalIdAfterPersistenceAndRefresh() {
        val source = parse(json("Original"))
        assertTrue(source.subscriptionUriIdentity)
        assertEquals(source.id, parse(json("Original")).id)
        val stored = SubscriptionProfileRefresh.apply(source, null, "local-id", "sub-id", source.id)
        val loaded = gson.fromJson(gson.toJson(stored), Profile::class.java).sanitize()
        val incoming = parse(json("Original"))
        val matched = SubscriptionIdentity.match(incoming, listOf(incoming), listOf(loaded), emptySet())
        assertNotNull(matched)
        assertEquals("local-id", SubscriptionProfileRefresh.apply(incoming, matched, matched!!.id, "sub-id", incoming.id).id)
    }

    @Test fun renamedAndRetunedIdlessJsonAndTunaPreserveLocalOverrides() {
        for (wrapped in listOf(false, true)) {
            val source = parse(json("Original"), wrapped)
            val first = SubscriptionProfileRefresh.apply(source, null, "local-id", "sub-id", source.id)
            val local = first.copy(name = "Personal title")
            val loaded = gson.fromJson(gson.toJson(local), Profile::class.java).sanitize()
            val incoming = parse(json("Remote rename", uri.replace("reuse=true", "reuse=false")), wrapped)
            assertNotEquals(source.id, incoming.id)
            assertTrue(incoming.subscriptionUriIdentity)
            val matched = SubscriptionIdentity.match(incoming, listOf(incoming), listOf(loaded), emptySet())
            assertEquals(loaded, matched)
            val refreshed = SubscriptionProfileRefresh.apply(incoming, matched, matched!!.id, "sub-id", incoming.id)
            assertEquals("local-id", refreshed.id)
            assertEquals("Personal title", refreshed.name)
            assertTrue((refreshed.kernelConfig as KernelConfig.Vpn).config.uri.contains("reuse=false"))
            val merged = SubscriptionProfileContinuity.afterRefresh(listOf(loaded), listOf(refreshed), "sub-id", "Fixture", setOf(loaded.id))
            assertEquals(listOf(refreshed), merged)
        }
    }

    @Test fun explicitServerIdsAndAmbiguousEndpointsAreNeverGuessed() {
        val explicit = parse(json("Original", id = "server-id"))
        assertFalse(explicit.subscriptionUriIdentity)
        val stored = SubscriptionProfileRefresh.apply(explicit, null, "local-id", "sub-id", explicit.id)
        val changedId = parse(json("Original", id = "different-server-id"))
        assertNull(SubscriptionIdentity.match(changedId, listOf(changedId), listOf(stored), emptySet()))

        val derived = parse(json("Derived"))
        val old = SubscriptionProfileRefresh.apply(derived, null, "derived-local", "sub-id", derived.id)
        val next = parse(json("Renamed"))
        val duplicateEndpoint = parse(json("Other name"))
        assertNull(SubscriptionIdentity.match(next, listOf(next, duplicateEndpoint), listOf(old), emptySet()))
        val differentAccount = parse(json("Renamed", uri.replace("fixture-password", "other-fixture")))
        assertNull(SubscriptionIdentity.match(differentAccount, listOf(differentAccount), listOf(old), emptySet()))
    }
}
