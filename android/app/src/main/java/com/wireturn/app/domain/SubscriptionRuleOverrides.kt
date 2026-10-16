package com.wireturn.app.domain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.data.RoutingPolicy
import com.wireturn.app.data.RoutingRule

/** Local choices are separate from the remote document and keyed by rule identity, never position. */
data class SubscriptionRuleOverrides(
    val disabledRules: Set<String> = emptySet(),
    val userRules: List<RoutingRule> = emptyList(),
    val enabledRules: Set<String> = emptySet(),
) {
    fun isEnabled(rule: RoutingRule): Boolean = when (key(rule)) {
        in disabledRules -> false
        in enabledRules -> true
        else -> rule.enabled
    }
    fun apply(source: RoutingPolicy): RoutingPolicy = source.copy(
        rules = userRules + source.rules.map { it.copy(enabled = isEnabled(it)) },
    ).also { it.validate() }

    fun toggle(rule: RoutingRule, enabled: Boolean) = copy(
        disabledRules = if (enabled) disabledRules - key(rule) else disabledRules + key(rule),
        enabledRules = if (enabled) enabledRules + key(rule) else enabledRules - key(rule),
    )

    /** Reset only decisions about remote rules, never the user's own rules or their order. */
    fun resetSource() = copy(disabledRules = emptySet(), enabledRules = emptySet())

    fun moveUserRule(index: Int, destination: Int): SubscriptionRuleOverrides {
        require(index in userRules.indices && destination in userRules.indices)
        return copy(userRules = userRules.toMutableList().apply { add(destination, removeAt(index)) })
    }

    fun toJson(): String = Gson().toJson(this)

    fun effectiveJson(source: RoutingPolicy): String = JsonParser.parseString(apply(source).toJson()).asJsonObject.apply {
        addProperty("localSubscriptionRules", true)
    }.toString()

    companion object {
        // Do not lowercase regexes or keywords: these can be case-sensitive matchers.
        fun key(rule: RoutingRule): String = "${rule.action.name}:${rule.match}"
        fun load(json: String?): SubscriptionRuleOverrides = if (json.isNullOrBlank()) SubscriptionRuleOverrides()
            else Gson().fromJson(json, SubscriptionRuleOverrides::class.java).also {
                require(it.disabledRules.size <= 2000) { "Слишком много отключённых правил" }
                require(it.enabledRules.size <= 2000 && it.enabledRules.intersect(it.disabledRules).isEmpty()) { "Некорректные переключатели правил" }
                RoutingPolicy(rules = it.userRules).validate()
            }
        fun effectivePolicy(json: String): RoutingPolicy? = json.takeIf { it.contains("\"localSubscriptionRules\"") }?.let {
            val root = JsonParser.parseString(it).asJsonObject
            if (root.get("localSubscriptionRules")?.asBoolean == true) RoutingPolicy.load(it, "", "") else null
        }
    }
}
