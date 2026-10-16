package com.wireturn.app.data

/** Global router, deliberately separate from the engine selected by a server profile. */
enum class NetworkRouter { XRAY, SING_BOX_EXTENDED }
enum class SplitDnsOverride { INHERIT, DISABLED, CUSTOM }

data class NetworkDnsSettings(
    // Keep the legacy list intact: migration must not invent new resolver routes.
    val servers: String = XraySettings.DEFAULT_DNS,
    val splitDnsJson: String = "",
    val fakeDns: Boolean = false,
    val bootstrap: String = "",
) {
    val overrideMode: SplitDnsOverride get() = when {
        splitDnsJson.isBlank() -> SplitDnsOverride.INHERIT
        splitDnsJson == "disabled" -> SplitDnsOverride.DISABLED
        else -> SplitDnsOverride.CUSTOM
    }
}

data class NetworkRoutingSettings(
    val policyJson: String = "",
    val legacyDirect: String = "",
    val legacyBlock: String = "",
    val subscriptionSource: String = "",
    val bypassLan: Boolean = false,
    val geoVariant: String = "runetfreedom",
)

/** No routing-disable flag here: only the existing local confirmation flow owns it. */
data class SharedNetworkSettings(
    val dns: NetworkDnsSettings = NetworkDnsSettings(),
    val routing: NetworkRoutingSettings = NetworkRoutingSettings(),
    val router: NetworkRouter = NetworkRouter.XRAY,
) {
    fun requireAvailableRouter() {
        require(router in NetworkRouter.entries) {
            "Неизвестный общий маршрутизатор"
        }
    }

    /** Compatibility bridge for existing screens and the Xray process launcher. */
    fun applyToLegacy(base: XraySettings): XraySettings {
        requireAvailableRouter()
        return base.copy(
            dns = dns.servers, splitDnsJson = dns.splitDnsJson, fakeDns = dns.fakeDns,
            bootstrapDns = dns.bootstrap,
            networkRouter = router,
            routingPolicyJson = routing.policyJson, routeDirect = routing.legacyDirect,
            routeBlock = routing.legacyBlock, happRoutingSource = routing.subscriptionSource,
            bypassLan = routing.bypassLan, geoVariant = routing.geoVariant,
        )
    }

    companion object {
        fun fromLegacy(s: XraySettings) = SharedNetworkSettings(
            dns = NetworkDnsSettings(s.dns, s.splitDnsJson, s.fakeDns, s.bootstrapDns),
            routing = NetworkRoutingSettings(s.routingPolicyJson, s.routeDirect, s.routeBlock,
                s.happRoutingSource, s.bypassLan, s.geoVariant),
            router = s.networkRouter,
        )
    }
}
