package com.wireturn.app.data.kernel

import com.google.gson.JsonParser
import com.wireturn.app.kernel.XrayVpnConfig
import com.wireturn.app.kernel.KernelRegistry
import java.net.URI
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class VlessFinalMaskTest {
    private fun uri(mask: String, transport: String = "xhttp") =
        "vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443?security=tls&type=$transport&fp=firefox&fm=" +
            URLEncoder.encode(mask, "UTF-8")

    @Test fun finalMaskSurvivesImportEditAndXrayGenerationWithoutLosingFields() {
        val mask = """{"quicParams":{"congestion":"bbr","bbrProfile":"balanced","debug":false,"maxIdleTimeout":30,"maxIncomingStreams":1024},"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200","interval":"10-20"}}]}"""
        for (transport in listOf("xhttp", "tcp")) {
            val source = uri(mask, transport)
            assertNotNull("fm import", KernelRegistry.decodeUri(source))
            val profile = requireNotNull(VpnConfig.parse(source))
            assertEquals("xray", CoreCapabilities.resolve(profile))
            val edited = VpnEditorDraft.from(profile.uri).change("server", "edited.invalid").toUri()
            val generated = XrayVpnConfig.generate(profile.copy(uri = edited), "127.0.0.1:1080", "u", "p", false)
            val params = URI(generated["link"].asString).rawQuery.split('&').associate {
                VpnConfig.decode(it.substringBefore('=')) to VpnConfig.decode(it.substringAfter('='))
            }
            assertEquals(JsonParser.parseString(mask), JsonParser.parseString(params.getValue("fm")))
            assertEquals(mask, params.getValue("fm"))
            val effectiveMask = NativeOptions.get(generated.getAsJsonObject("outbound_patch"), "streamSettings.finalmask")!!.asJsonObject
            val fragment = effectiveMask.getAsJsonArray("tcp")[0].asJsonObject.getAsJsonObject("settings")
            assertEquals("10-20", fragment["delay"].asString)
            assertFalse(fragment.has("interval"))
            assertEquals("edited.invalid", URI(generated["link"].asString).host)
        }
    }

    @Test fun malformedFinalMaskIsRejectedInsteadOfSilentlyIgnored() {
        for (mask in listOf("", "null", "[]", "true", "{bad}", "{} {}", "{\"quicParams\":{},}")) {
            assertNull(mask, VpnConfig.parse(uri(mask)))
        }
    }

    @Test fun explicitExtendedSelectionExplainsFinalMaskIncompatibility() {
        val imported = requireNotNull(VpnConfig.parse(uri("{\"quicParams\":{\"congestion\":\"bbr\"}}")))
        val error = runCatching { imported.copy(engine = "sing-box").validate() }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("Xray"))
        assertTrue(error.message.orEmpty().contains("fm"))
    }
}
