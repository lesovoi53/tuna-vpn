package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * Read-through migration: existing keys remain canonical for subscription updates,
 * restore and older APKs. Never maintain a second, potentially stale JSON snapshot.
 * Writes touch only network settings, not profiles, listeners or routing consent.
 */
internal object NetworkPreferences {
    private val bootstrap = androidx.datastore.preferences.core.stringPreferencesKey("network_bootstrap_dns")
    private val router = androidx.datastore.preferences.core.stringPreferencesKey("network_router")
    fun read(p: Preferences) = SharedNetworkSettings(
        router = NetworkRouter.XRAY,
        dns = NetworkDnsSettings(
            servers = p[AppPreferences.XRAY_DNS] ?: XraySettings.DEFAULT_DNS,
            splitDnsJson = p[AppPreferences.XRAY_SPLIT_DNS] ?: "",
            fakeDns = p[AppPreferences.XRAY_FAKEDNS] ?: false,
            bootstrap = p[bootstrap] ?: "",
        ),
        routing = NetworkRoutingSettings(
            policyJson = p[AppPreferences.XRAY_ROUTING_POLICY] ?: "",
            legacyDirect = p[AppPreferences.XRAY_ROUTE_DIRECT] ?: "",
            legacyBlock = p[AppPreferences.XRAY_ROUTE_BLOCK] ?: "",
            subscriptionSource = p[AppPreferences.XRAY_HAPP_SOURCE] ?: "",
            bypassLan = p[AppPreferences.XRAY_BYPASS_LAN] ?: false,
            geoVariant = p[AppPreferences.XRAY_GEO_VARIANT] ?: "runetfreedom",
        ),
    )

    fun write(p: MutablePreferences, s: SharedNetworkSettings) {
        s.requireAvailableRouter()
        BootstrapDns.parse(s.dns.bootstrap)
        p.remove(router)
        if (s.dns.bootstrap.isBlank()) p.remove(bootstrap) else p[bootstrap] = s.dns.bootstrap.trim()
        p[AppPreferences.XRAY_DNS] = s.dns.servers
        p[AppPreferences.XRAY_SPLIT_DNS] = s.dns.splitDnsJson
        p[AppPreferences.XRAY_FAKEDNS] = s.dns.fakeDns
        p[AppPreferences.XRAY_ROUTING_POLICY] = s.routing.policyJson
        p[AppPreferences.XRAY_ROUTE_DIRECT] = s.routing.legacyDirect
        p[AppPreferences.XRAY_ROUTE_BLOCK] = s.routing.legacyBlock
        p[AppPreferences.XRAY_HAPP_SOURCE] = s.routing.subscriptionSource
        p[AppPreferences.XRAY_BYPASS_LAN] = s.routing.bypassLan
        p[AppPreferences.XRAY_GEO_VARIANT] = s.routing.geoVariant
    }
}
