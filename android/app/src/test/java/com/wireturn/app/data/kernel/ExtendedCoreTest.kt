package com.wireturn.app.data.kernel

import com.wireturn.app.kernel.SingBoxConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.Base64

class ExtendedCoreTest {
    private val base="vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=tls&sni=example.com"
    private fun fixture(name: String, uri: String) {
        val profile=VpnConfig(uri,"sing-box")
        assertTrue(profile.isValid())
        val dir=File("build/vpn-test-configs").apply { mkdirs() }
        File(dir,"extended-$name.json").writeText(SingBoxConfig.generate(profile,"127.0.0.1:2081","local-user","local-pass").toString())
    }
    @Test fun explicitExtendedSupportsXhttpButAutoRetainsXray() {
        for(mode in listOf("auto","packet-up","stream-up","stream-one")) {
            val uri="$base&type=xhttp&mode=$mode&path=%2Ftuna"
            assertTrue(VpnConfig(uri).usesXray)
            val extended=VpnConfig(uri,"sing-box")
            assertFalse(extended.usesXray)
            val transport=extended.outbound().getAsJsonObject("transport")
            assertEquals(mode,transport["mode"].asString)
            assertEquals("100-1000",transport["x_padding_bytes"].asString)
            fixture("xhttp-$mode",uri)
        }
        assertEquals("xhttp",VpnConfig("$base&type=splithttp","sing-box").outbound().getAsJsonObject("transport")["type"].asString)
    }
    @Test fun xhttpExtraIsConvertedWithoutDroppingUnknownOptions() {
        val extra="""{"sessionIDPlacement":"cookie","sessionIDKey":"sess","sessionIDTable":"Base62","sessionIDLength":"16-32","xPaddingBytes":"100-500","noGRPCHeader":true,"xmux":{"maxConcurrency":"1-3"}}"""
        val uri="$base&type=xhttp&extra="+URLEncoder.encode(extra,"UTF-8")
        val out=VpnConfig(uri,"sing-box").outbound().getAsJsonObject("transport")
        assertEquals("cookie",out["session_placement"].asString)
        assertEquals("sess",out["session_key"].asString)
        assertEquals("1-3",out.getAsJsonObject("xmux")["max_concurrency"].asString)
        fixture("xhttp-extra",uri)
        for (unsupported in listOf("""{"downloadSettings":{}}""", """{"invented":1}""", """{"sessionKey":"a","sessionIDKey":"b"}""")) {
            assertFalse(VpnConfig("$base&type=xhttp&extra="+URLEncoder.encode(unsupported,"UTF-8"),"sing-box").isValid())
        }
    }
    @Test fun encryptionIsPreservedForBothTcpAndXhttp() {
        val key=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it+1).toByte() })
        val encryption="mlkem768x25519plus.native.1rtt.$key"
        for (type in listOf("tcp","xhttp")) {
            val uri="$base&type=$type&encryption=$encryption"
            assertEquals(encryption,VpnConfig(uri,"sing-box").outbound()["encryption"].asString)
            assertTrue(VpnConfig(uri).usesXray)
            fixture("encrypted-$type",uri)
        }
        assertFalse(VpnConfig("$base&encryption=invalid","sing-box").isValid())
    }
    @Test fun xrayMuxMustNotBecomeIncompatibleSingMux() {
        assertFalse(VpnConfig("$base&mux=8","sing-box").isValid())
        assertTrue(VpnConfig("$base&mux=0","sing-box").isValid())
        assertFalse(VpnConfig("$base&extra=%7B%7D","sing-box").isValid())
        assertEquals("sing-box-extended",VpnConfig(base,"sing-box").engineLabel)
    }
}
