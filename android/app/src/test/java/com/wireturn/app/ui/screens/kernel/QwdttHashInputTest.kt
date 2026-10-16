package com.wireturn.app.ui.screens.kernel

import org.junit.Assert.assertEquals
import org.junit.Test

class QwdttHashInputTest {
    @Test fun keepsRawHashes() {
        assertEquals("test-hash", qwdttHashInput("  test-hash  "))
    }

    @Test fun acceptsVkCallLinks() {
        for (host in listOf("vk.com", "vk.ru", "m.vk.com", "m.vk.ru")) {
            assertEquals("test-hash", qwdttHashInput("https://$host/call/join/test-hash?source=test"))
        }
    }

    @Test fun doesNotExtractFromUnrelatedUrls() {
        for (link in listOf("https://example.com/call/join/test-hash", "https://vk.ru/other/test-hash")) {
            assertEquals(link, qwdttHashInput(link))
        }
    }
}
