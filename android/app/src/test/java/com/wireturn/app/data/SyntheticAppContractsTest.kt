package com.wireturn.app.data

import com.wireturn.app.data.kernel.*
import com.wireturn.app.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Offline synthetic matrix: fake credentials, reserved hosts and caller-controlled time. */
class SyntheticAppContractsTest {
    @Test fun protocolEditorsPreserveEscapedCredentialsAcrossNamesAndPorts() {
        for ((protocol, _) in VpnEditorDraft.manualProtocols) for (port in listOf("1", "443", "65535")) {
            var d=VpnEditorDraft.create(protocol).change("server","example.invalid").change("port",port)
                .change("credential",if(protocol=="vless") "00000000-0000-4000-8000-000000000001" else "p:@/+#% test")
                .change("password","p:@/+#% test")
            if(protocol=="tuic") d=d.change("username","00000000-0000-4000-8000-000000000001")
            if(protocol in setOf("mieru", "naive", "trusttunnel")) d=d.change("username","test:user")
            if (protocol in setOf("awg", "wireguard")) {
                for ((key,n) in mapOf("private_key" to 251, "public_key" to 41))
                    d = d.change(d.fields.first { it.queryName == key }.key, java.util.Base64.getEncoder().encodeToString(ByteArray(32) { n.toByte() }))
            }
            val uri=d.toUri(); val parsed=requireNotNull(VpnConfig.parse(uri)) { protocol }
            parsed.validate()
            assertEquals(protocol,parsed.protocol)
            assertEquals(uri,VpnEditorDraft.from(uri).toUri())
        }
    }
    @Test fun doubleConfirmationRemainsRequiredAcrossRepeatedCancelledAttempts() {
        val flow=RoutingDisableConsent.Confirmation()
        repeat(50) { i ->
            val now=i*3000L
            assertNull(flow.confirm(now));flow.continueToSecondStep(now)
            assertNull(flow.confirm(now+649));flow.cancel();assertNull(flow.confirm(now+1000))
        }
        flow.continueToSecondStep(200000);val grant=flow.confirm(200650)!!
        assertTrue(grant.consume());assertFalse(grant.consume());assertNull(flow.confirm(300000))
    }
    @Test fun impossibleMeasurementsNeverWinEitherMode() {
        for(type in GroupTestType.entries) {
            val g=ConnectionGroup("g","Synthetic",ConnectionCategory.VPN,type,listOf("a","b"))
            val invalid=listOf(
                GroupMeasurement("a",-1,100.0,1000),GroupMeasurement("a",1,100.0,2001),
                GroupMeasurement("foreign",1,100.0,1000),GroupMeasurement("b",1,100.0,1000,"failed"))
            assertNull(GroupSelection().choose(g,invalid,null,2000))
        }
    }
    @Test fun allFailedRoundBreaksConsecutiveImprovementConfirmations() {
        val g=ConnectionGroup("g","Synthetic",ConnectionCategory.VPN,GroupTestType.URL_TEST,
            listOf("a","b"),holdSeconds=0,confirmations=2,improvementMs=10)
        val selection=GroupSelection()
        fun values(t:Long)=listOf(GroupMeasurement("a",200,measuredAt=t),GroupMeasurement("b",20,measuredAt=t))
        assertEquals("a",selection.choose(g,values(1000),"a",1000))
        assertNull(selection.choose(g,emptyList(),"a",2000))
        assertEquals("a",selection.choose(g,values(3000),"a",3000))
        assertEquals("b",selection.choose(g,values(4000),"a",4000))
    }

    @Test fun failureRoundBreaksLatencyImprovementStreak() {
        val g=ConnectionGroup("g","G",ConnectionCategory.VPN,GroupTestType.URL_TEST,
            listOf("a","b"),holdSeconds=0,confirmations=2,improvementMs=10)
        val selector=GroupSelection()
        fun pair(t:Long)=listOf(GroupMeasurement("a",200,measuredAt=t),GroupMeasurement("b",20,measuredAt=t))
        assertEquals("a",selector.choose(g,pair(1000),"a",1000))
        assertEquals("a",selector.choose(g,listOf(GroupMeasurement("b",20,measuredAt=2000)),"a",2000))
        assertEquals("a",selector.choose(g,pair(3000),"a",3000))
        assertEquals("b",selector.choose(g,pair(4000),"a",4000))
    }
}
