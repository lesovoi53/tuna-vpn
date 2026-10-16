package com.wireturn.app.data.kernel

import com.google.gson.JsonParser
import com.wireturn.app.domain.VlessFingerprintImport
import com.wireturn.app.kernel.XrayVpnConfig
import java.io.File
import java.net.URI
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class XhttpPacketUpImportTest {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun query(uri: String) = URI(uri).rawQuery.split('&').associate {
        VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('='))
    }

    @Test fun packetUpVariantsPreserveFieldsAndUseOuterTransportPath() {
        for ((index, placement) in listOf("body", "header", "body").withIndex()) {
            val extra = JsonParser.parseString("""{
              "headers":{"User-Agent":"Mozilla/5.0 Chrome/120.0.0.0","Accept":"application/json, */*"},
              "xPaddingBytes":"50-150","scMaxEachPostBytes":"524288-1048576",
              "scMinPostsIntervalMs":"5-10","noGRPCHeader":true,"noSSEHeader":true,
              "scMaxBufferedPosts":100,"uplinkDataPlacement":"$placement",
              "uplinkDataKey":"xq","uplinkChunkSize":"7500","uplinkHTTPMethod":"${if (index == 0) "POST" else "GET"}",
              "seqPlacement":"query","seqKey":"chunk_id","xPaddingObfsMode":true,
              "xPaddingPlacement":"queryInHeader","xPaddingMethod":"tokenish","xPaddingHeader":"X-Cache",
              "serverMaxHeaderBytes":32768,"sessionIDPlacement":"query","sessionIDKey":"auth",
              "sessionIDLength":"16-32","sessionIDTable":"Base62",
              "xmux":{"maxConcurrency":"16-32","cMaxReuseTimes":"100","cMaxLifetimeMs":300000},
              "path":"/wrong-extra/","host":"wrong.invalid","mode":"stream-up"
            }""").asJsonObject
            val uri = "vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443/?type=xhttp&encryption=none&flow=&path=%2Fapi%2F$index%2F&host=vpn.invalid&mode=packet-up&extra=${enc(extra.toString())}&security=tls&sni=vpn.invalid&alpn=h2%2Chttp%2F1.1&fp=${if(index==0) "firefox" else "edge"}#Test"
            val imported = requireNotNull(VpnConfig.parse(uri))
            val saved = VlessFingerprintImport.vpn(imported).sanitize()
            assertEquals(uri, saved.uri)
            assertEquals(uri, VpnEditorDraft.from(saved.uri).toUri())
            assertEquals("xray", saved.selectedEngine)
            val config = XrayVpnConfig.generate(saved,"127.0.0.1:21880","test","test",false)
            val normalized = query(config["link"].asString)
            val effective = JsonParser.parseString(normalized.getValue("extra")).asJsonObject
            assertEquals("/api/$index/", effective["path"].asString)
            assertEquals("vpn.invalid", effective["host"].asString)
            assertEquals("packet-up", effective["mode"].asString)
            for ((key, value) in extra.entrySet().filter { it.key !in setOf("path","host","mode") }) {
                val expected = value.deepCopy()
                if (key == "xmux") expected.asJsonObject.remove("cMaxLifetimeMs")
                assertEquals(key,expected,effective[key])
            }
            assertTrue(VpnEnginePolicy.note(saved)!!.contains("cMaxLifetimeMs"))
            assertEquals("", normalized["flow"])
            File("build/release-fixtures").mkdirs()
            File("build/release-fixtures/xhttp-packet-$index.json").writeText(config.toString())
        }
    }
}
