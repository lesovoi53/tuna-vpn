package com.wireturn.app.domain

import com.wireturn.app.R
import com.wireturn.app.data.kernel.VpnEditorDraft
import org.junit.Assert.*
import org.junit.Test

class ProtocolGuideTest {
    @Test fun vpnGuideCoversEveryManualProfileWithoutAdvertisingBinaryOnlyProtocols() {
        assertEquals(VpnEditorDraft.manualProtocols.map { it.first }.toSet(),
            ProtocolGuide.entries.filter { it.category == R.string.protocol_guide_vpn }.map { it.id }.toSet())
        assertEquals(ProtocolGuide.entries.size, ProtocolGuide.entries.map { it.id }.toSet().size)
        assertEquals(R.string.protocol_guide_vpn, ProtocolGuide.entries.single { it.id == "wireguard" }.category)
        assertTrue(ProtocolGuide.entries.all { it.source.startsWith("https://") })
    }
}
