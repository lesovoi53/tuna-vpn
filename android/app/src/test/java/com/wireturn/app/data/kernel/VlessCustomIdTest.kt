package com.wireturn.app.data.kernel

import com.wireturn.app.kernel.XrayVpnConfig
import com.wireturn.app.data.KernelConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.net.URI
import java.io.File

class VlessCustomIdTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val extra = """{"xPaddingBytes":"50-150","scMaxEachPostBytes":"3000000","noGRPCHeader":true,"seqPlacement":"query","seqKey":"chunk_id","xPaddingObfsMode":true,"xPaddingHeader":"X-Padding","xPaddingPlacement":"header","xPaddingMethod":"tokenish","sessionPlacement":"query","sessionKey":"auth","sessionIDPlacement":"query","sessionIDKey":"auth","sessionIDTable":"Base64","sessionIDLength":"16-32","xmux":{"maxConcurrency":"64-128","maxConnections":"0","cMaxReuseTimes":"0","hMaxRequestTimes":"1000-2000","hMaxReusableSecs":"300-600","hKeepAlivePeriod":15},"mode":"auto"}"""
    private fun link(id: String) = "vless://${enc(id)}@192.0.2.1:443/?type=xhttp&encryption=none&flow=&host=example.com&path=%2Fapi%2Fraw%2FMedia%2FCorporate_Overview_1080p.mp4&mode=auto&extra=${enc(extra)}&security=tls&sni=example.com&alpn=h2%2Chttp%2F1.1&fp=firefox#Fixture"

    @Test fun shortCustomIdImportsAndPreservesXhttpParametersThroughEditorAndRuntime() {
        val uri = link("synthetic-id-1234")
        val p = VpnConfig.parse(uri) ?: error("Short VLESS IDs must import")
        assertTrue(KernelConfig.parseUri(uri) is KernelConfig.Vpn)
        assertEquals("xray", p.engine)
        assertEquals(uri, p.uri)
        val edited = VpnConfig.parse(VpnEditorDraft.from(uri).change("port", "8443").toUri())!!
        assertEquals("synthetic-id-1234", VpnConfig.decode(URI(edited.uri).rawUserInfo))
        val generated = XrayVpnConfig.generate(p, "127.0.0.1:21089", "fixture", "fixture", false)
        val runtime = generated["link"].asString
        val q = URI(runtime).rawQuery.split('&').associate { VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('=')) }
        val actualExtra = com.google.gson.JsonParser.parseString(q.getValue("extra")).asJsonObject
        val expected = com.google.gson.JsonParser.parseString(extra).asJsonObject.apply { addProperty("host", "example.com"); addProperty("path", "/api/raw/Media/Corporate_Overview_1080p.mp4") }
        assertEquals(expected, actualExtra)
        File("build/release-fixtures").mkdirs()
        File("build/release-fixtures/vless-custom-id.json").writeText(generated.toString())
    }

    @Test fun percentEncodedUnicodeIdUsesXrayMappingAndOriginalIsUnchanged() {
        val uri = link("我爱🍉老师1314")
        val p = VpnConfig.parse(uri) ?: error("UTF-8 custom ID must import")
        val runtime = XrayVpnConfig.generate(p, "127.0.0.1:21089", "fixture", "fixture", false)["link"].asString
        assertEquals("5783a3e7-e373-51cd-8642-c83782b807c5", URI(runtime).userInfo)
        assertEquals(uri, p.uri)
        val extended = p.copy(engine = "sing-box").outbound()
        assertEquals("5783a3e7-e373-51cd-8642-c83782b807c5", extended["uuid"].asString)
    }

    @Test fun customIdBoundaryUsesBytesRatherThanCharacters() {
        for (id in listOf("a".repeat(30), "я".repeat(15), "00000000000040008000000000000001"))
            assertNotNull(VpnConfig.parse(link(id)))
        for (id in listOf("", "a".repeat(31), "я".repeat(16), "z".repeat(36)))
            assertNull(VpnConfig.parse(link(id)))
    }
}
