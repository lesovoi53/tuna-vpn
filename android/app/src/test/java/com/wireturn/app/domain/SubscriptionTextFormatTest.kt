package com.wireturn.app.domain

import com.wireturn.app.data.kernel.VpnEditorDraft
import org.junit.Assert.*
import org.junit.Test

class SubscriptionTextFormatTest {
    @Test fun naiveOnlySubscriptionIsRecognizedBeforeParsingEndpoints() {
        for (scheme in listOf("naive+https", "NAIVE+HTTPS")) {
            val text = "$scheme://user:password@example.com:443#Name"
            assertTrue(SubscriptionTextFormat.recognizes(text))
            assertTrue(SubscriptionEntryFilter.supported(text))
        }
    }
    @Test fun everyManualProtocolReachesTheTextParser() {
        for ((protocol, _) in VpnEditorDraft.manualProtocols) {
            val link = VpnEditorDraft.create(protocol).change("server", "example.invalid").change("port", "443")
                .change("username", "00000000-0000-4000-8000-000000000001").change("password", "password")
                .change("credential", "00000000-0000-4000-8000-000000000001").toUri()
            assertTrue(protocol, SubscriptionTextFormat.recognizes(link))
        }
        assertTrue(SubscriptionTextFormat.recognizes("#name: Test\nunknown://invalid"))
        assertTrue("Unknown protocols must reach the parser to produce a rejection report",
            SubscriptionTextFormat.recognizes("unknown://invalid"))
        assertFalse(SubscriptionEntryFilter.supported("unknown://invalid"))
        assertFalse(SubscriptionTextFormat.recognizes("<html>error</html>"))
    }
}
