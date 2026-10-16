package com.wireturn.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactorTest {
    @Test fun connectionLinksJsonCredentialsHeadersAndIdsAreHidden() {
        val source = """vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443?token=SECRET
            https://subscription.invalid/private/subscription-token
            {"password":"secret with spaces", "privateKey":"abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG="}
            Authorization: Bearer very-private-token
            --password 'private cli value'
            id=00000000-0000-4000-8000-000000000002 failed: timeout 35000 ms
        """.trimIndent()
        val safe = DiagnosticRedactor.clean(source)
        listOf("subscription-token", "very-private-token", "private cli value", "secret with spaces", "00000000-0000", "abcdefghijklmnopqrstuvwxyz", "SECRET").forEach { assertFalse(it, safe.contains(it)) }
        assertTrue(safe.contains("failed: timeout 35000 ms"))
        assertEquals(safe, DiagnosticRedactor.clean(safe))
    }
    @Test fun ordinaryErrorsAndPacketCountersRemainUseful() {
        val text = "Subscription Error: IllegalArgumentException; Conflicting group snapshot revision; bytes=2048, timeout=5000"
        assertEquals(text, DiagnosticRedactor.clean(text))
    }
    @Test fun singBoxAuthenticatedInboundHidesShortUserButPreservesConnectionDetails() {
        val source = "INFO[0142] [1234567 0ms] inbound/socks[local]: [qa-short-user] inbound connection from 127.0.0.1:54321"
        val safe = DiagnosticRedactor.clean(source)
        assertFalse(safe.contains("qa-short-user"))
        assertTrue(safe.contains("[1234567 0ms] inbound/socks[local]:"))
        assertTrue(safe.contains("inbound connection from 127.0.0.1:54321"))
        assertEquals(safe, DiagnosticRedactor.clean(safe))
        val anonymous = "inbound/socks[local]: inbound connection from 127.0.0.1:54321"
        assertEquals(anonymous, DiagnosticRedactor.clean(anonymous))
    }
    @Test fun namedProtocolCredentialsAreHiddenEvenWhenShort() {
        val source = """{"psk":"short-psk", "userkey":"short-key", "pre_shared_key":"short-shared", "header_protection_key":"short-header", "username":"short-login"}
            -proxy-user cli-login -proxy-pass cli-secret
        """.trimIndent()
        val safe = DiagnosticRedactor.clean(source)
        listOf("short-psk", "short-key", "short-shared", "short-header", "short-login", "cli-login", "cli-secret").forEach {
            assertFalse(it, safe.contains(it))
        }
        assertEquals(safe, DiagnosticRedactor.clean(safe))
    }
}
