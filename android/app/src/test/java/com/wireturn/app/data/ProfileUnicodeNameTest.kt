package com.wireturn.app.data

import org.junit.Assert.*
import org.junit.Test

class ProfileUnicodeNameTest {
    @Test fun nameLimitMustNotSplitEmojiOrCorruptExportedText() {
        val name = "A" + "😀".repeat(60)
        val actual = Profile("unicode", name).sanitize().name
        assertEquals(name, actual)
        assertEquals(actual, actual.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    @Test fun longNamesRemainBoundedByCodePoints() {
        val actual = Profile("unicode", "A" + "😀".repeat(150)).sanitize().name
        assertEquals(100, actual.codePointCount(0, actual.length))
        assertEquals(actual, actual.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
}
