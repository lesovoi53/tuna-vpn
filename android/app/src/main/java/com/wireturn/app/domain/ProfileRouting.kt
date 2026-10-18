package com.wireturn.app.domain

import com.wireturn.app.data.*

/** Select from one persisted snapshot. A subscription never writes the common rule set. */
object ProfileRouting {
    fun common(settings: SharedNetworkSettings, subscriptions: List<Subscription>): SharedNetworkSettings {
        val source = settings.routing.subscriptionSource
        if (source.isBlank() || subscriptions.none { it.routingSource == source }) return settings
        // Older versions copied the source before subscription emergency defaults
        // existed. Match both exact generated representations, never strip fields
        // from a user override to make it resemble the subscription.
        val copiedDns = runCatching {
            val policy = SubscriptionPolicyParser.parseHapp(source)
            setOf(SplitDnsSettings.fromPolicy(policy).toJson(),
                (policy.dns ?: SplitDnsSettings(policy.remote, policy.domestic, policy.hosts)).toJson())
        }.getOrDefault(emptySet())
        return settings.copy(routing = settings.routing.copy(subscriptionSource = ""),
            dns = if (settings.dns.splitDnsJson in copiedDns) settings.dns.copy(splitDnsJson = "") else settings.dns)
    }
    fun enabled(subscription: Subscription): Boolean = subscription.useSubscriptionRouting
        ?: (subscription.routingRecommended && !subscription.routingSource.isNullOrBlank())

    // Server routing is applied automatically on every successful import/refresh.
    // Manual rule overrides remain independent; no reconnect is requested here.
    @Suppress("UNUSED_PARAMETER")
    fun afterImport(previous: Subscription?, source: String, recommended: Boolean = false): Boolean = source.isNotBlank()

    fun resolve(common: SharedNetworkSettings, profile: Profile?, subscriptions: List<Subscription>): SharedNetworkSettings {
        val sub = subscriptions.firstOrNull { it.id == profile?.subscriptionId }
        val source = if (sub != null && enabled(sub)) {
            require(!sub.routingSource.isNullOrBlank()) { "У подписки включена маршрутизация, но правила отсутствуют" }
            SubscriptionPolicyParser.parseHapp(sub.routingSource)
            sub.routingSource
        } else common.routing.subscriptionSource
        val selected = common.copy(router = NetworkRouter.XRAY,
            routing = common.routing.copy(subscriptionSource = source,
                policyJson = if (sub != null && enabled(sub) && !sub.routingOverridesJson.isNullOrBlank())
                    SubscriptionRuleOverrides.load(sub.routingOverridesJson).effectiveJson(SubscriptionPolicyParser.parseHapp(source).routing)
                else common.routing.policyJson),
            // Subscription DNS is part of its policy, not a global override.
            dns = if (source.isNotBlank()) common.dns.copy(splitDnsJson = "", fakeDns = false) else common.dns)
        return TcpOnlyDns.forProfile(selected, profile?.kernelConfig)
    }
}

