package com.wireturn.app.domain

import com.wireturn.app.data.RoutingPolicy
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.SplitDnsOverride

/** Shared precedence rules; engine-specific assets and process arguments stay outside. */
object NetworkPolicyResolver {
    data class Resolved(
        val routing: RoutingPolicy,
        val subscription: SubscriptionPolicyParser.HappPolicy?,
        val useLegacyFakeDns: Boolean,
        private val settings: SharedNetworkSettings,
    ) {
        fun applyOverrides(basePolicyJson: String): String = RoutingOverrides.applyLan(
            SplitDnsSettings.applyToPolicy(if (subscription != null) {
                com.google.gson.JsonParser.parseString(basePolicyJson).asJsonObject.apply {
                    add("rules", com.google.gson.Gson().toJsonTree(routing.rules))
                }.toString()
            } else basePolicyJson, settings.dns.splitDnsJson),
            settings.routing.bypassLan,
        )
    }

    fun resolve(settings: SharedNetworkSettings): Resolved {
        settings.requireAvailableRouter()
        return resolveForCompilation(settings)
    }

    /** Offline compilers may target a router whose Android lifecycle is not enabled yet. */
    internal fun resolveForCompilation(settings: SharedNetworkSettings): Resolved {
        val source = settings.routing.subscriptionSource
        val subscription = source.takeIf(String::isNotBlank)?.let(SubscriptionPolicyParser::parseHapp)
        val routing = subscription?.let { SubscriptionRuleOverrides.effectivePolicy(settings.routing.policyJson) ?: it.routing } ?: RoutingPolicy.load(settings.routing.policyJson,
            settings.routing.legacyDirect, settings.routing.legacyBlock)
        return Resolved(routing, subscription,
            settings.dns.fakeDns && subscription == null &&
                settings.dns.overrideMode == SplitDnsOverride.INHERIT, settings)
    }
}
