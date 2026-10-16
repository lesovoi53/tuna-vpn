package com.wireturn.app.data.kernel

import org.junit.Assert.*
import org.junit.Test

class SnellLinkContractTest {
    @Test fun serverLinkV5AndObfuscationSurviveImportEditAndReimport() {
        for (version in listOf(4, 5)) for (mode in listOf("none", "http", "tls")) for (tfo in listOf(false, true)) {
            val obfs = if (mode == "none") "" else "&obfs-host=yandex.ru&obfs-mode=$mode"
            val link = "snell://p%40ss%3Aword%2B%2F%3D@192.0.2.1:19552/?reuse=true&tfo=$tfo&version=$version$obfs#0"
            val parsed = VpnConfig.parse(link)!!
            val out = parsed.outbound()
            assertEquals(4, out["version"].asInt)
            assertEquals(mode, out["obfs_mode"].asString)
            assertEquals("p@ss:word+/=", out["psk"].asString)
            assertEquals("0", parsed.displayName)
            assertEquals(link, parsed.uri)
            assertEquals(link, VpnEditorDraft.from(link).toUri())
            val changed = VpnEditorDraft.from(link).change("server", "192.0.2.2").toUri()
            val reimported = VpnConfig.parse(changed)!!.outbound()
            assertEquals(out["obfs_mode"], reimported["obfs_mode"])
            assertEquals(out["obfs_host"], reimported["obfs_host"])
            assertEquals(tfo, reimported["tcp_fast_open"].asBoolean)
            assertTrue(reimported["reuse"].asBoolean)
        }
    }
}
