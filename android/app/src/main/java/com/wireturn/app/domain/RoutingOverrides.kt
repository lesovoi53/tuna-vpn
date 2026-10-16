package com.wireturn.app.domain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.data.*

object RoutingOverrides {
    val lanCidrs = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10")
    fun withLan(policy: RoutingPolicy, enabled: Boolean): RoutingPolicy {
        if (!enabled) return policy
        // User rules retain their exact order and precedence; LAN is before fallback only.
        val active = policy.activeRules()
        require(active.size + lanCidrs.size <= 500) { "Для обхода LAN оставьте не более 494 активных правил" }
        return policy.copy(mode = if (policy.mode == RoutingMode.ALL_VPN) RoutingMode.SELECTED_DIRECT else policy.mode,
            rules = active + lanCidrs.map { RoutingRule(it, RouteAction.DIRECT) })
    }
    fun applyLan(json: String, enabled: Boolean): String {
        if (!enabled) return json
        val root = JsonParser.parseString(json).asJsonObject
        val policy = withLan(RoutingPolicy.load(json, "", ""), true)
        root.addProperty("mode", policy.mode.name)
        root.add("rules", Gson().toJsonTree(policy.rules))
        return root.toString()
    }
}
