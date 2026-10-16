package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.GsonBuilder
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.domain.SubscriptionPolicyParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionImportCommitTest {
    @Test fun rejectionReportCommitsWithoutChangingExistingProfilesOrActiveRuntime() {
        val sub = nextSubscriptions.single()
        val p = preferences().apply {
            this[AppPreferences.PROFILES_JSON] = gson.toJson(nextProfiles)
            this[AppPreferences.SUBSCRIPTIONS_JSON] = gson.toJson(listOf(sub))
        }
        val before = p.toPreferences()
        val snapshot = SubscriptionImportSnapshot(nextProfiles, listOf(sub), "selected-profile",
            p[AppPreferences.PROFILES_JSON]!!, p[AppPreferences.SUBSCRIPTIONS_JSON]!!)
        val report = SubscriptionImportReport(rejected = listOf(SubscriptionImportIssue("Rejected", "wg", "Invalid parameters")))
        SubscriptionImportCommit.write(p, snapshot, nextProfiles, listOf(sub.copy(importReport = report)), null, gson)
        assertEquals(report, gson.fromJson(p[AppPreferences.SUBSCRIPTIONS_JSON], Array<Subscription>::class.java).single().importReport)
        for ((key, value) in before.asMap()) if (key != AppPreferences.SUBSCRIPTIONS_JSON)
            assertEquals("Report changed $key", value, p.asMap()[key])
    }

    @Test fun staleSubscriptionCannotOverwriteReportOrProfiles() {
        val p = preferences()
        val before = snapshot(p)
        p[AppPreferences.SUBSCRIPTIONS_JSON] = gson.toJson(nextSubscriptions)
        val current = p.toPreferences()
        val report = SubscriptionImportReport(rejected = listOf(SubscriptionImportIssue("Rejected", "wg", "Invalid parameters")))
        assertThrows(IllegalStateException::class.java) {
            SubscriptionImportCommit.write(p, before, nextProfiles,
                nextSubscriptions.map { it.copy(importReport = report) }, null, gson)
        }
        assertEquals(current, p.toPreferences())
    }

    @Test fun favoriteAddedAfterSnapshotRetainsVanishedServerAtomically() {
        val old = com.wireturn.app.domain.SubscriptionProfileRefresh.apply(Profile("source", "Fixture", KernelConfig.Vpn(
            com.wireturn.app.data.kernel.VpnConfig("vless://11111111-2222-4333-8444-555555555555@edge.example:443?security=tls&fp=firefox"))), null, "local", "sub", "source")
        val sub = Subscription("sub", "Fixture sub", "https://example.invalid/sub")
        val p = preferences().apply {
            this[AppPreferences.PROFILES_JSON] = gson.toJson(listOf(old))
            this[AppPreferences.SUBSCRIPTIONS_JSON] = gson.toJson(listOf(sub))
        }
        val before = SubscriptionImportSnapshot(listOf(old), listOf(sub), "unrelated", p[AppPreferences.PROFILES_JSON]!!,
            p[AppPreferences.SUBSCRIPTIONS_JSON]!!)
        val favorites = androidx.datastore.preferences.core.stringSetPreferencesKey("profile_favorites_v1")
        p[favorites] = setOf(com.wireturn.app.domain.ProfileListOrder.key(old))
        SubscriptionImportCommit.write(p, before, emptyList(), listOf(sub), null, gson)
        val retained = gson.fromJson(p[AppPreferences.PROFILES_JSON], Array<Profile>::class.java).single()
        assertEquals(old.id, retained.id)
        assertNull(retained.subscriptionId)
        assertEquals("Fixture sub", retained.retainedFromSubscription)
        assertTrue(com.wireturn.app.domain.ProfileListOrder.isFavorite(retained, p[favorites].orEmpty()))
        assertEquals("active-runtime-snapshot", p[AppPreferences.ACTIVE_KERNEL_JSON])
    }
    @Test fun refreshedTextLinkKeepsFavoriteAfterItsSourceHashChanges() {
        val oldUri = "vless://11111111-2222-4333-8444-555555555555@edge.example:443?security=tls&type=xhttp#Old"
        val nextUri = oldUri.substringBefore('#') + "#New"
        fun sourceId(uri: String) = java.util.UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
        val old = Profile("local", "Old", KernelConfig.Vpn(com.wireturn.app.data.kernel.VpnConfig(oldUri)),
            subscriptionId = "sub", subscriptionSourceId = sourceId(oldUri), subscriptionUriIdentity = true)
        val incoming = old.copy(id = sourceId(nextUri), name = "New", subscriptionId = null,
            subscriptionSourceId = null, kernelConfig = KernelConfig.Vpn(com.wireturn.app.data.kernel.VpnConfig(nextUri)))
        val matched = com.wireturn.app.domain.SubscriptionIdentity.match(incoming, listOf(incoming), listOf(old), emptySet())!!
        val fresh = com.wireturn.app.domain.SubscriptionProfileRefresh.apply(incoming, matched, matched.id, "sub", incoming.id)
        val sub = Subscription("sub", "Fixture", "https://example.invalid/sub")
        val p = preferences().apply {
            this[AppPreferences.PROFILES_JSON] = gson.toJson(listOf(old))
            this[AppPreferences.SUBSCRIPTIONS_JSON] = gson.toJson(listOf(sub))
        }
        val favorites = androidx.datastore.preferences.core.stringSetPreferencesKey("profile_favorites_v1")
        p[favorites] = setOf(com.wireturn.app.domain.ProfileListOrder.key(old), "profile:unrelated")
        val before = SubscriptionImportSnapshot(listOf(old), listOf(sub), "local",
            p[AppPreferences.PROFILES_JSON]!!, p[AppPreferences.SUBSCRIPTIONS_JSON]!!)
        SubscriptionImportCommit.write(p, before, listOf(fresh), listOf(sub), null, gson)
        assertEquals(old.id, fresh.id)
        assertNotEquals(old.subscriptionSourceId, fresh.subscriptionSourceId)
        assertTrue("A renamed text subscription server must remain a favorite",
            com.wireturn.app.domain.ProfileListOrder.isFavorite(fresh, p[favorites].orEmpty()))
        assertTrue("Other favorite marks must survive", "profile:unrelated" in p[favorites].orEmpty())
    }

    @Test fun subscriptionRefreshPreservesLocalFavoritesAndSorting() {
        val p = preferences()
        val favorites = androidx.datastore.preferences.core.stringSetPreferencesKey("profile_favorites_v1")
        val sort = stringPreferencesKey("profile_sort_v1")
        p[favorites] = setOf("subscription:3:sub:6:server")
        p[sort] = "NAME_DESC"
        SubscriptionImportCommit.write(p, snapshot(p), nextProfiles, nextSubscriptions, null, gson)
        assertEquals(setOf("subscription:3:sub:6:server"), p[favorites])
        assertEquals("NAME_DESC", p[sort])
    }
    @Test fun ownedRoutingImportNeverOverwritesCommonDnsRulesOrConsent() {
        val p = preferences()
        val before = p.toPreferences()
        val sub = Subscription("owned", "Owned", "https://example.com/sub", routingSource = source(), useSubscriptionRouting = true)
        SubscriptionImportCommit.write(p, snapshot(p), nextProfiles, listOf(sub), null, gson)
        for ((key, value) in before.asMap()) if (key != AppPreferences.PROFILES_JSON && key != AppPreferences.SUBSCRIPTIONS_JSON)
            assertEquals("Global setting changed: $key", value, p.asMap()[key])
        assertTrue(p[AppPreferences.SUBSCRIPTIONS_JSON]!!.contains("useSubscriptionRouting"))
    }
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private val nextProfiles = listOf(Profile(id = "next-profile", name = "New profile"))
    private val nextSubscriptions = listOf(Subscription(id = "subscription", name = "New name", url = "https://example.invalid/sub"))
    private val consent = booleanPreferencesKey("routing_disabled_after_confirmation_v1")
    private val bootstrap = stringPreferencesKey("network_bootstrap_dns")
    private val groups = stringPreferencesKey("connection_groups_v1")

    private fun source(remote: String = "192.0.2.53"): String = "happ://routing/onadd/" +
        Base64.getEncoder().encodeToString("""{
            "Name":"Commit fixture","GlobalProxy":"true","RouteOrder":"block-proxy-direct",
            "RemoteDNSType":"TCP","RemoteDns":"$remote",
            "DomesticDNSType":"UDP","DomesticDns":"192.0.2.54",
            "BlockSites":["domain:blocked.example"],"DirectSites":["domain:direct.example"]
        }""".toByteArray())

    private fun preferences() = mutablePreferencesOf(
        AppPreferences.PROFILES_JSON to "[]", AppPreferences.SUBSCRIPTIONS_JSON to "[]",
        AppPreferences.CURRENT_PROFILE_ID to "selected-profile",
        AppPreferences.ACTIVE_KERNEL_JSON to "active-runtime-snapshot",
        AppPreferences.XRAY_HAPP_SOURCE to "previous-source",
        AppPreferences.XRAY_DNS to "tcp://192.0.2.60",
        AppPreferences.XRAY_SPLIT_DNS to "disabled", AppPreferences.XRAY_FAKEDNS to true,
        AppPreferences.XRAY_ROUTING_POLICY to "local-policy",
        AppPreferences.XRAY_ROUTE_DIRECT to "domain:local.example",
        AppPreferences.XRAY_AUTH_ENABLED to false,
        AppPreferences.XRAY_USER to "original-user", AppPreferences.XRAY_PASS to "original-secret",
        bootstrap to "tcp://192.0.2.61", consent to true, groups to "[]",
    )

    private fun snapshot(p: MutablePreferences) = SubscriptionImportSnapshot(
        emptyList(), emptyList(), p[AppPreferences.CURRENT_PROFILE_ID] ?: "default",
        p[AppPreferences.PROFILES_JSON] ?: "[]", p[AppPreferences.SUBSCRIPTIONS_JSON] ?: "[]",
    )

    @Test fun staleProfileOrSubscriptionSnapshotLeavesEverythingUntouched() {
        for (key in listOf(AppPreferences.PROFILES_JSON, AppPreferences.SUBSCRIPTIONS_JSON)) {
            val p = preferences()
            val beforeImport = snapshot(p)
            p[key] = "[\"concurrent user edit\"]"
            val concurrentState = p.toPreferences()
            val error = runCatching {
                SubscriptionImportCommit.write(p, beforeImport, nextProfiles, nextSubscriptions, source(), gson)
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(concurrentState, p.toPreferences())
        }
    }

    @Test fun successCommitsProfilesSubscriptionsAndRoutingUsingCurrentCredentials() {
        val p = preferences()
        val beforeImport = snapshot(p)
        // Credentials can change while the subscription payload is being downloaded/merged.
        p[AppPreferences.XRAY_USER] = "current-user"
        p[AppPreferences.XRAY_PASS] = "current-secret"
        val routingSource = source()
        SubscriptionImportCommit.write(p, beforeImport, nextProfiles, nextSubscriptions, routingSource, gson)
        assertEquals(gson.toJson(nextProfiles), p[AppPreferences.PROFILES_JSON])
        assertEquals(gson.toJson(nextSubscriptions), p[AppPreferences.SUBSCRIPTIONS_JSON])
        assertEquals(routingSource, p[AppPreferences.XRAY_HAPP_SOURCE])
        assertEquals(SplitDnsSettings.fromPolicy(SubscriptionPolicyParser.parseHapp(routingSource)),
            SplitDnsSettings.parse(p[AppPreferences.XRAY_SPLIT_DNS]!!))
        assertEquals(false, p[AppPreferences.XRAY_FAKEDNS])
        assertEquals(true, p[AppPreferences.XRAY_AUTH_ENABLED])
        assertEquals("current-user", p[AppPreferences.XRAY_USER])
        assertEquals("current-secret", p[AppPreferences.XRAY_PASS])
        assertEquals(true, p[consent])
        assertEquals("tcp://192.0.2.60", p[AppPreferences.XRAY_DNS])
        assertEquals("tcp://192.0.2.61", p[bootstrap])
        assertEquals("local-policy", p[AppPreferences.XRAY_ROUTING_POLICY])
        assertEquals("domain:local.example", p[AppPreferences.XRAY_ROUTE_DIRECT])
        assertEquals("[]", p[groups])
        assertEquals("selected-profile", p[AppPreferences.CURRENT_PROFILE_ID])
        assertEquals("active-runtime-snapshot", p[AppPreferences.ACTIVE_KERNEL_JSON])
    }

    @Test fun malformedRoutingAndInvalidSplitDnsLeaveAllPreferencesUntouched() {
        for (routingSource in listOf("not a routing URI", source("resolver.example"))) {
            val p = preferences()
            val before = p.toPreferences()
            assertTrue(runCatching {
                SubscriptionImportCommit.write(p, snapshot(p), nextProfiles, nextSubscriptions, routingSource, gson)
            }.isFailure)
            assertEquals(before, p.toPreferences())
        }
    }

    @Test fun invalidCurrentBootstrapFailsBeforeProfilesOrRoutingAreWritten() {
        val p = preferences().apply { this[bootstrap] = "tcp://resolver.example" }
        val before = p.toPreferences()
        assertTrue(runCatching {
            SubscriptionImportCommit.write(p, snapshot(p), nextProfiles, nextSubscriptions, source(), gson)
        }.isFailure)
        assertEquals(before, p.toPreferences())
    }

    @Test fun noRoutingLeavesDnsAuthConsentAndAllOtherKeysExactlyUnchanged() {
        val p = preferences()
        val before = p.toPreferences().asMap()
        SubscriptionImportCommit.write(p, snapshot(p), nextProfiles, nextSubscriptions, null, gson)
        val changed = setOf(AppPreferences.PROFILES_JSON, AppPreferences.SUBSCRIPTIONS_JSON)
        assertEquals(before.filterKeys { it !in changed }, p.asMap().filterKeys { it !in changed })
        assertEquals(gson.toJson(nextProfiles), p[AppPreferences.PROFILES_JSON])
        assertEquals(gson.toJson(nextSubscriptions), p[AppPreferences.SUBSCRIPTIONS_JSON])
    }

    @Test fun routingGeneratesOnlyMissingCredentialsAndPreservesExistingPasswordExactly() {
        val p = preferences().apply {
            remove(AppPreferences.XRAY_USER)
            this[AppPreferences.XRAY_PASS] = " exact existing credential "
        }
        SubscriptionImportCommit.write(p, snapshot(p), nextProfiles, nextSubscriptions, source(), gson)
        assertEquals(16, p[AppPreferences.XRAY_USER]!!.length)
        assertEquals(" exact existing credential ", p[AppPreferences.XRAY_PASS])
        val generated = p[AppPreferences.XRAY_USER]
        SubscriptionImportCommit.activateRouting(p, source())
        assertEquals(generated, p[AppPreferences.XRAY_USER])
        assertEquals(" exact existing credential ", p[AppPreferences.XRAY_PASS])
    }
}
