package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.wireturn.app.domain.SplitDnsSettings
import com.wireturn.app.domain.SubscriptionPolicyParser

internal data class SubscriptionImportSnapshot(
    val profiles: List<Profile>,
    val subscriptions: List<Subscription>,
    val currentProfileId: String,
    val profilesJson: String,
    val subscriptionsJson: String,
    val protectedProfileIds: Set<String> = setOf(currentProfileId),
)

/** One edit commits all imported state, or rejects a stale plan without touching preferences. */
internal object SubscriptionImportCommit {
    fun protectedIds(preferences: Preferences, profiles: List<Profile>, gson: Gson): Set<String> {
        val favorites = preferences[androidx.datastore.preferences.core.stringSetPreferencesKey("profile_favorites_v1")].orEmpty()
        val groups = decodeStoredGroups(preferences[androidx.datastore.preferences.core.stringPreferencesKey("connection_groups_v1")] ?: "[]", profiles)
        return setOfNotNull(preferences[AppPreferences.CURRENT_PROFILE_ID]) + groups.flatMap { it.memberIds + it.routingProfileId } +
            profiles.filter { com.wireturn.app.domain.ProfileListOrder.isFavorite(it, favorites) }.map { it.id }
    }
    fun write(
        preferences: MutablePreferences,
        snapshot: SubscriptionImportSnapshot,
        profiles: List<Profile>,
        subscriptions: List<Subscription>,
        routingSource: String?,
        gson: Gson,
        routingSubscriptionId: String? = null,
    ) {
        check((preferences[AppPreferences.PROFILES_JSON] ?: "[]") == snapshot.profilesJson &&
            (preferences[AppPreferences.SUBSCRIPTIONS_JSON] ?: "[]") == snapshot.subscriptionsJson) {
            "Профили или подписки изменились во время импорта. Повторите обновление."
        }
        // Serialization and every potentially rejecting routing operation precede all writes.
        // Re-read references inside the transaction: a favorite/group can be edited
        // while the response is downloading without changing PROFILES_JSON.
        val protectedProfiles = snapshot.protectedProfileIds + protectedIds(preferences, snapshot.profiles, gson)
        val committedProfiles = profiles + subscriptions.flatMap { sub ->
            com.wireturn.app.domain.SubscriptionProfileContinuity.retainedMissing(snapshot.profiles, profiles,
                sub.id, sub.name, protectedProfiles)
        }
        val profilesJson = gson.toJson(committedProfiles)
        val subscriptionsJson = gson.toJson(subscriptions)
        val routing = routingSource?.let { prepareRouting(preferences, it) }
        // Only a successful routing-bearing import may clear the global off switch.
        // Report-only writes and unrelated subscriptions never grant activation.
        if (routingSubscriptionId != null) {
            val owner = requireNotNull(subscriptions.singleOrNull { it.id == routingSubscriptionId })
            require(com.wireturn.app.domain.ProfileRouting.enabled(owner))
            SubscriptionPolicyParser.parseHapp(requireNotNull(owner.routingSource))
        }
        val hasGroups = (snapshot.subscriptions + subscriptions).any { sub ->
            !sub.tunaDocument.isNullOrBlank() && com.google.gson.JsonParser.parseString(sub.tunaDocument)
                .asJsonObject.getAsJsonArray("groups")?.size()?.let { it > 0 } == true
        }
        val groupsKey = androidx.datastore.preferences.core.stringPreferencesKey("connection_groups_v1")
        val existingGroupJson = preferences[groupsKey] ?: "[]"
        val groupsJson = run {
            val existing = decodeStoredGroups(existingGroupJson, snapshot.profiles)
            val merged = if (hasGroups) com.wireturn.app.domain.SubscriptionGroupMerge.merge(existing, snapshot.subscriptions,
                subscriptions, snapshot.profiles, committedProfiles) else existing
            val reconciled = com.wireturn.app.domain.SubscriptionGroupMerge.reconcileLocal(merged, committedProfiles)
            // Decoding can already repair obsolete members. Compare against persisted
            // JSON, not the repaired in-memory list, so that repair is actually committed.
            val encoded = gson.toJson(reconciled)
            if (runCatching { com.google.gson.JsonParser.parseString(existingGroupJson) }.getOrNull() ==
                com.google.gson.JsonParser.parseString(encoded)) null else encoded
        }

        val favoritesKey = androidx.datastore.preferences.core.stringSetPreferencesKey("profile_favorites_v1")
        val favorites = preferences[favoritesKey].orEmpty()
        val nextFavorites = com.wireturn.app.domain.ProfileListOrder.afterRefresh(snapshot.profiles, committedProfiles, favorites)

        if (routing != null) writeRouting(preferences, routing)
        if (routingSubscriptionId != null)
            preferences.remove(androidx.datastore.preferences.core.booleanPreferencesKey("routing_disabled_after_confirmation_v1"))
        if (groupsJson != null) preferences[groupsKey] = groupsJson
        if (nextFavorites != favorites) preferences[favoritesKey] = nextFavorites
        preferences[AppPreferences.PROFILES_JSON] = profilesJson
        preferences[AppPreferences.SUBSCRIPTIONS_JSON] = subscriptionsJson
    }

    fun activateRouting(preferences: MutablePreferences, source: String) {
        writeRouting(preferences, prepareRouting(preferences, source))
    }

    private data class PreparedRouting(val network: SharedNetworkSettings, val user: String, val password: String)

    private fun prepareRouting(preferences: Preferences, source: String): PreparedRouting {
        val policy = SubscriptionPolicyParser.parseHapp(source)
        val splitDns = SplitDnsSettings.fromPolicy(policy).also { it.validate() }
        val current = NetworkPreferences.read(preferences)
        val network = current.copy(
            routing = current.routing.copy(subscriptionSource = source),
            dns = current.dns.copy(splitDnsJson = splitDns.toJson(), fakeDns = false),
        )
        // NetworkPreferences.write performs these same checks before assigning its first key.
        network.requireAvailableRouter()
        BootstrapDns.parse(network.dns.bootstrap)
        val user = preferences[AppPreferences.XRAY_USER].orEmpty()
        val password = preferences[AppPreferences.XRAY_PASS].orEmpty()
        val defaults = if (user.isBlank() || password.isBlank()) {
            XraySettings(isProxyAuthEnabled = true).fillDefaults()
        } else null
        return PreparedRouting(network, user.ifBlank { defaults!!.proxyUser }, password.ifBlank { defaults!!.proxyPass })
    }

    private fun writeRouting(preferences: MutablePreferences, routing: PreparedRouting) {
        NetworkPreferences.write(preferences, routing.network)
        preferences[AppPreferences.XRAY_AUTH_ENABLED] = true
        preferences[AppPreferences.XRAY_USER] = routing.user
        preferences[AppPreferences.XRAY_PASS] = routing.password
    }
}
