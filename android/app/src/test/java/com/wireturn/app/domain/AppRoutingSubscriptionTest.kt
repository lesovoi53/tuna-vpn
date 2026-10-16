package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AppRoutingSubscriptionTest {
    private val link = "tuna://app-exclusions/v1?mode=vpn&packages=com.android.settings"
    private fun full(name: String = "test", mode: String = "vpn"): String {
        val json = """{"version":1,"policy":{"Name":"$name","RemoteDns":"1.1.1.1","DomesticDns":"8.8.8.8"},"apps":{"mode":"$mode","packages":["com.android.settings"]}}"""
        return "tuna://routing/v1?data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }
    @Test fun fullRoutingPlainAndBase64MixedWithServersLegacyAndApps() {
        val full = full()
        val happ = TunaRoutingLink.parse(full).source
        val text = "snell://test@example.org:1234\n$full\n$happ\n$link\n$full"
        for (input in listOf(text, Base64.getEncoder().encodeToString(text.toByteArray()))) {
            val result = AppRoutingSubscription.extract(input)
            assertEquals("snell://test@example.org:1234", result.content)
            assertEquals(TunaRoutingLink.parse(full), result.fullRouting)
            assertEquals(happ, result.legacyRouting)
            assertEquals(AppExclusionsLink.command(link), result.command)
        }
        assertTrue(AppRoutingSubscription.extract("$full\n$happ").content.isBlank())
    }
    @Test fun fullRoutingConflictsMalformedAndAppDisagreementRejectEntireDocument() {
        for (text in listOf("${full()}\n${full("other")}", "${full(mode = "bypass")}\n$link",
            "snell://test@example.org:1234\ntuna://routing/v1?data=broken")) {
            assertTrue(runCatching { AppRoutingSubscription.extract(text) }.isFailure)
        }
    }
    @Test fun textAndBase64CarrySameCommandWithoutProfileEntry() {
        val text = "snell://test@example.org:1234\n$link"
        for (input in listOf(text, Base64.getEncoder().encodeToString(text.toByteArray()))) {
            val result = AppRoutingSubscription.extract(input)
            assertEquals("snell://test@example.org:1234", result.content)
            assertEquals(AppExclusionsLink.command(link), result.command)
        }
    }
    @Test fun rulesOnlyAndDuplicateCommands() {
        val result = AppRoutingSubscription.extract("$link\n$link")
        assertTrue(result.content.isBlank())
        assertEquals(AppExclusionsLink.command(link), result.command)
    }
    @Test fun conflictingOrInvalidCommandsRejectWholeExtraction() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingSubscription.extract("$link\n${link.replace("mode=vpn", "mode=bypass")}")
        }
        assertThrows(IllegalArgumentException::class.java) { AppRoutingSubscription.extract("$link&mode=bad") }
    }
    @Test fun ordinaryDocumentsRemainUnchanged() {
        for (text in listOf("{\"profiles\":[]}", "snell://test@example.org:1234", "YWJj")) {
            assertEquals(AppRoutingSubscription.Result(text, null), AppRoutingSubscription.extract(text))
        }
    }
}
