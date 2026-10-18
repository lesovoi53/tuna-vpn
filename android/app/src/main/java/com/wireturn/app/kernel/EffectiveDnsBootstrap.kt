package com.wireturn.app.kernel

import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.SplitDnsOverride
import com.wireturn.app.domain.NetworkPolicyResolver
import com.wireturn.app.domain.SplitDnsSettings

/** The same policy precedence as compilation, also used by isolated profile tests. */
object EffectiveDnsBootstrap {
    fun resolve(settings: SharedNetworkSettings, rawXray: Boolean = false): String {
        // Full Xray JSON retains the historical explicit global transport bootstrap,
        // but must not acquire a subscription's split-DNS bootstrap override.
        if (rawXray) return settings.dns.bootstrap
        val split = when (settings.dns.overrideMode) {
            SplitDnsOverride.DISABLED -> null
            SplitDnsOverride.CUSTOM -> SplitDnsSettings.parse(settings.dns.splitDnsJson)
            SplitDnsOverride.INHERIT -> NetworkPolicyResolver.resolve(settings).subscription?.let(SplitDnsSettings::fromPolicy)
        }
        return when (split?.bootstrap?.mode) {
            "system" -> "system"
            "resolver" -> "tcp://${requireNotNull(split.bootstrap.resolver).endpoint}"
            else -> settings.dns.bootstrap
        }
    }

    fun changed(previous: SharedNetworkSettings, current: SharedNetworkSettings, rawXray: Boolean = false): Boolean =
        resolve(previous, rawXray) != resolve(current, rawXray)
}
