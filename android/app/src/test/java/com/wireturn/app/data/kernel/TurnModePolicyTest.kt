package com.wireturn.app.data.kernel

import org.junit.Assert.*
import org.junit.Test

class TurnModePolicyTest {
    private val hash = "test_hash_1234567890"
    @Test fun qwdttFollowsAddedAndRemovedHashes() {
        val empty = TurnModePolicy.open(QwdttConfig())
        assertTrue(empty.accountAuth)
        val filled = TurnModePolicy.edit(empty, listOf(hash, ""))
        assertFalse(filled.accountAuth)
        assertEquals(listOf(hash), filled.hashList)
        assertTrue(TurnModePolicy.edit(filled, listOf("")).accountAuth)
        assertFalse(TurnModePolicy.hasHashes(listOf("short")))
    }
}
