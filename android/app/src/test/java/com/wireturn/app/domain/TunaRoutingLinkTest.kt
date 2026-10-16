package com.wireturn.app.domain

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TunaRoutingLinkTest {
    private val policy = """{"Name":"Тест","RemoteDns":"1.1.1.1","DomesticDns":"8.8.8.8","DirectSites":["domain:example.com"],"GlobalProxy":false}"""
    private fun link(json: String) = "tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    private fun rejected(json: String) { assertTrue(runCatching { TunaRoutingLink.parse(link(json)) }.isFailure) }

    @Test fun parsesFullPolicyAppsAndPreviewWithoutMutation() {
        val uri = link("""{"version":1,"policy":$policy,"apps":{"mode":"vpn","packages":["org.telegram.messenger"]}}""")
        val command = TunaRoutingLink.parse(uri)
        assertEquals("Тест", command.policy.name)
        assertFalse(command.apps!!.bypass)
        assertEquals(com.wireturn.app.data.RoutingMode.SELECTED_VPN, command.policy.routing.mode)
        assertTrue(previewDeepLink(uri) is DeepLinkPreview.Routing)
    }

    @Test fun rejectsUnknownDuplicateVersionMalformedAndUnsupportedFields() {
        rejected("""{"version":2,"policy":$policy}""")
        rejected("""{"version":"1","policy":$policy}""")
        rejected("""{"version":1,"version":1,"policy":$policy}""")
        rejected("""{"version":1,"policy":$policy,"other":true}""")
        rejected("""{"version":1,"policy":${policy.dropLast(1)},"FakeDNS":true}}""")
        rejected("""{"version":1,"policy":${policy.dropLast(1)},"UseChunkFiles":true}}""")
        rejected("""{"version":1,"policy":${policy.dropLast(1)},"Unknown":false}}""")
        rejected("""{version:1,"policy":$policy}""")
        assertTrue(runCatching { TunaRoutingLink.parse("tuna://routing/v1?data=%%%") }.isFailure)
        assertTrue(runCatching { TunaRoutingLink.parse(link("""{"version":1,"policy":$policy}""") + "&data=x") }.isFailure)
    }

    @Test fun enforcesSizeRuleAndAppLimits() {
        rejected("""{"version":1,"policy":${policy.dropLast(1)},"Name":"${"x".repeat(262144)}"}}""")
        val rules = (1..501).joinToString(",") { "\"domain:e$it.com\"" }
        rejected("""{"version":1,"policy":{"RemoteDns":"1.1.1.1","DomesticDns":"8.8.8.8","DirectSites":[$rules]}}""")
        val packages = (1..101).joinToString(",") { "\"org.app.p$it\"" }
        rejected("""{"version":1,"policy":$policy,"apps":{"mode":"vpn","packages":[$packages]}}""")
        rejected("""{"version":1,"policy":$policy,"apps":{"mode":"vpn","packages":["org.a,org.b"]}}""")
    }
}
