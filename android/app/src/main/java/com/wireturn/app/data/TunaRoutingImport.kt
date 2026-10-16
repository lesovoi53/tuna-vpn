package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wireturn.app.domain.SubscriptionPolicyParser
import com.wireturn.app.domain.SubscriptionRuleOverrides
import com.wireturn.app.domain.TunaRoutingLink

/** Pure import planning keeps validation outside the single settings transaction. */
internal object TunaRoutingImport {
    fun plan(before: SharedNetworkSettings, command: TunaRoutingLink.Command): SharedNetworkSettings {
        val local = RoutingPolicy.load(before.routing.policyJson, before.routing.legacyDirect, before.routing.legacyBlock)
        val oldSourceRules = before.routing.subscriptionSource.takeIf(String::isNotBlank)
            ?.let { SubscriptionPolicyParser.parseHapp(it).routing.rules }.orEmpty()
        val manual = local.rules.filter { it !in oldSourceRules }
        val merged = SubscriptionRuleOverrides(userRules = manual).effectiveJson(command.policy.routing)
        return before.copy(
            router = NetworkRouter.XRAY,
            routing = before.routing.copy(policyJson = merged, subscriptionSource = command.source),
            dns = before.dns.copy(splitDnsJson = "", fakeDns = false),
        )
    }

    fun write(p: MutablePreferences, before: SharedNetworkSettings, after: SharedNetworkSettings,
        command: TunaRoutingLink.Command) {
        check(NetworkPreferences.read(p) == before) { "Настройки изменились. Повторите импорт" }
        // Retain a recoverable snapshot of replaced DNS/source settings.
        p[stringPreferencesKey("tuna_routing_import_previous_v1")] = com.google.gson.Gson().toJson(before)
        NetworkPreferences.write(p, after)
        p.remove(androidx.datastore.preferences.core.booleanPreferencesKey("routing_disabled_after_confirmation_v1"))
        command.apps?.let { AppRoutingLists.apply(p, it) }
    }
}
