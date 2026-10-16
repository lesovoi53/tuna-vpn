package com.wireturn.app.domain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.data.*
import com.wireturn.app.kernel.XrayNetworkCompiler
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionRuleOverridesTest {
    @Test fun unmarkedCommonPolicyIsNotParsedButMarkedCorruptionFailsClosed() {
        assertNull(SubscriptionRuleOverrides.effectivePolicy("invalid unused local policy"))
        assertThrows(Exception::class.java) { SubscriptionRuleOverrides.effectivePolicy("""{"localSubscriptionRules":true,"rules":oops}""") }
        assertNotEquals(GroupProbeSettings(), GroupProbeSettings(subscriptionRuleOverrides = mapOf("sub" to "changed")))
    }
    @Test fun resetSourceKeepsOwnRulesAndTheirOrderAndEnabledState() {
        val own = listOf(user.copy(enabled = false), user.copy(match = "domain:other.example"))
        val edited = SubscriptionRuleOverrides(userRules = own).toggle(disabled, false)
            .toggle(disabled.copy(match = "domain:another.example", enabled = false), true)
        val reset = SubscriptionRuleOverrides.load(edited.toJson()).resetSource()
        assertEquals(own, reset.userRules)
        assertTrue(reset.disabledRules.isEmpty())
        assertTrue(reset.enabledRules.isEmpty())
        assertEquals(own.reversed(), reset.moveUserRule(0, 1).userRules)
    }

    @Test fun explicitlyEnablingDisabledSourceRuleOverridesItsDefault() {
        val sourceRule = disabled.copy(enabled = false)
        assertFalse(SubscriptionRuleOverrides().isEnabled(sourceRule))
        assertTrue(SubscriptionRuleOverrides().toggle(sourceRule, true).isEnabled(sourceRule))
        assertFalse(SubscriptionRuleOverrides().toggle(sourceRule, true).resetSource().isEnabled(sourceRule))
    }

    @Test fun legacyOverridesWithoutEnabledSetLoadSafely() {
        val legacy = """{"disabledRules":[],"userRules":[]}"""
        assertEquals(SubscriptionRuleOverrides(), SubscriptionRuleOverrides.load(legacy))
    }
    private val disabled = RoutingRule("domain:blocked.example", RouteAction.BLOCK)
    private val user = RoutingRule("domain:allowed.blocked.example", RouteAction.VPN)
    private fun source(rules: String = "\"domain:blocked.example\"") = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(
        """{"RemoteDns":"192.0.2.53","DomesticDns":"192.0.2.54","BlockSites":[$rules]}""".toByteArray())

    @Test fun disablingFollowsIdentityAcrossRefreshAndUserRulesStayFirst() {
        val saved = SubscriptionRuleOverrides(userRules = listOf(user)).toggle(disabled, false)
        val restored = SubscriptionRuleOverrides.load(saved.toJson())
        val updated = SubscriptionPolicyParser.parseHapp(source("\"domain:new.example\",\"domain:blocked.example\"")).routing
        val actual = restored.apply(updated)
        assertEquals(user, actual.rules.first())
        assertTrue(actual.rules[1].enabled)
        assertFalse(actual.rules[2].enabled)
        assertTrue(restored.toggle(disabled, true).apply(updated).rules.last().enabled)
        assertTrue(restored.apply(RoutingPolicy(rules = listOf(disabled.copy(action = RouteAction.DIRECT)))).rules.last().enabled)
    }

    @Test fun oldSubscriptionDefaultsAndJsonRoundTripPreserveOverrides() {
        val old = Gson().fromJson("""{"id":"s","name":"S","url":"https://example.com"}""", Subscription::class.java)
        assertEquals(SubscriptionRuleOverrides(), SubscriptionRuleOverrides.load(old.routingOverridesJson))
        val saved = old.copy(routingOverridesJson = SubscriptionRuleOverrides(userRules = listOf(user)).toJson())
        assertEquals(saved, Gson().fromJson(Gson().toJson(saved), Subscription::class.java))
    }

    @Test fun compilerAppliesOnlySelectedSubscriptionOverridesAndRetainsDnsAndSource() {
        val overrides = SubscriptionRuleOverrides(userRules = listOf(user)).toggle(disabled, false)
        val sub = Subscription("s", "S", "https://example.com", routingSource = source(), useSubscriptionRouting = true, routingOverridesJson = overrides.toJson())
        val common = SharedNetworkSettings(routing = NetworkRoutingSettings(policyJson = RoutingPolicy(rules = listOf(RoutingRule("domain:common.example", RouteAction.DIRECT))).toJson()))
        val effective = ProfileRouting.resolve(common, Profile("p", "P", subscriptionId = "s"), listOf(sub))
        val compiled = XrayNetworkCompiler.compile(effective, false)
        assertEquals(sub.routingSource, compiled.subscriptionSource)
        assertEquals(listOf(user), RoutingPolicy.load(compiled.policyJson, "", "").activeRules())
        val dns = JsonParser.parseString(compiled.policyJson).asJsonObject.getAsJsonObject("splitDns")
        assertEquals("192.0.2.53", dns.getAsJsonObject("remote").get("endpoint").asString)
        assertEquals(common, ProfileRouting.resolve(common, Profile("p", "P"), listOf(sub)))
        assertEquals(common, ProfileRouting.resolve(common, Profile("p", "P", subscriptionId = "s"), listOf(sub.copy(useSubscriptionRouting = false))))
    }

    @Test fun invalidAndOversizedUserRulesFailClosed() {
        assertTrue(runCatching { SubscriptionRuleOverrides.load(SubscriptionRuleOverrides(userRules = listOf(user.copy(match = "bad value"))).toJson()) }.isFailure)
        assertTrue(runCatching { SubscriptionRuleOverrides(userRules = List(500) { user }).apply(RoutingPolicy(rules = listOf(disabled))) }.isFailure)
    }
}
