package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.wireturn.app.domain.TunaRoutingLink
import com.wireturn.app.domain.NetworkPolicyResolver
import com.wireturn.app.kernel.XrayNetworkCompiler
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TunaRoutingImportTest {
    private fun command(): TunaRoutingLink.Command {
        val json = """{"version":1,"policy":{"RemoteDns":"1.1.1.1","DomesticDns":"8.8.8.8","GlobalProxy":false,"BlockSites":["domain:ads.example"]},"apps":{"mode":"vpn","packages":["org.new.app"]}}"""
        return TunaRoutingLink.parse("tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()))
    }

    @Test fun persistsEffectiveManualRulesDnsAndAdditiveAppsTogether() {
        val p = mutablePreferencesOf()
        val manual = RoutingRule("domain:mine.example", RouteAction.DIRECT)
        val before = SharedNetworkSettings(routing = NetworkRoutingSettings(policyJson = RoutingPolicy(rules = listOf(manual)).toJson()))
        NetworkPreferences.write(p, before)
        AppRoutingLists.apply(p, com.wireturn.app.domain.AppExclusionsLink.Command(false, setOf("org.existing.app")))
        val command = command()
        val after = TunaRoutingImport.plan(before, command)
        TunaRoutingImport.write(p, before, after, command)
        val stored = NetworkPreferences.read(p)
        val effective = NetworkPolicyResolver.resolve(stored)
        assertEquals(manual, effective.routing.rules.first())
        assertEquals(2, effective.routing.rules.size)
        assertEquals(RoutingMode.SELECTED_VPN, effective.routing.mode)
        val native = XrayNetworkCompiler.compile(stored, false)
        assertTrue(native.policyJson.contains("splitDns"))
        assertEquals(setOf("org.existing.app", "org.new.app"), p[AppPreferences.VPN_EXCLUDED_APPS])
        // Reimporting does not accumulate source rules.
        assertEquals(2, NetworkPolicyResolver.resolve(TunaRoutingImport.plan(stored, command)).routing.rules.size)
    }

    @Test fun concurrentChangeRejectsBeforeWritingAnything() {
        val p = mutablePreferencesOf()
        val before = NetworkPreferences.read(p)
        val command = command()
        val after = TunaRoutingImport.plan(before, command)
        p[AppPreferences.XRAY_DNS] = "9.9.9.9"
        val snapshot = p.toPreferences()
        assertTrue(runCatching { TunaRoutingImport.write(p, before, after, command) }.isFailure)
        assertEquals(snapshot, p.toPreferences())
    }
}
