package com.wireturn.app.kernel

import com.wireturn.app.data.RoutingPolicy
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.domain.NetworkPolicyResolver
import com.wireturn.app.domain.SubscriptionRoutingAssets

enum class FakeDnsOwner { HEV, XRAY, NONE }

/** Compiles the policy consumed by our packaged Xray adapter, not arbitrary Xray JSON. */
object XrayNetworkCompiler {
    data class Compiled(
        val policyJson: String,
        val routing: RoutingPolicy,
        val subscriptionSource: String,
        val dnsServers: String,
        val fakeDnsOwner: FakeDnsOwner,
    )

    fun compile(settings: SharedNetworkSettings, hevEnabled: Boolean): Compiled {
        require(settings.router == com.wireturn.app.data.NetworkRouter.XRAY) { "Выбран другой общий маршрутизатор" }
        val resolved = NetworkPolicyResolver.resolve(settings)
        val source = settings.routing.subscriptionSource
        val base = if (resolved.subscription != null) SubscriptionRoutingAssets.nativePolicy(source)
            else resolved.routing.toJson()
        val owner = when {
            hevEnabled -> FakeDnsOwner.HEV
            resolved.useLegacyFakeDns -> FakeDnsOwner.XRAY
            else -> FakeDnsOwner.NONE
        }
        val policy = resolved.applyOverrides(base)
        com.google.gson.JsonParser.parseString(policy).asJsonObject.get("splitDns")?.let {
            com.wireturn.app.domain.SplitDnsSettings.parse(it.toString()).validateForRouter(settings.router)
        }
        return Compiled(policy, resolved.routing, source, settings.dns.servers, owner)
    }
}
