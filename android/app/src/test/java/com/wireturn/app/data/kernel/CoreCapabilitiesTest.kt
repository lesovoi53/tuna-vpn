package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.wireturn.app.kernel.SingBoxConfig
import com.wireturn.app.kernel.XrayVpnConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoreCapabilitiesTest {
    @Test fun xrayQuicFieldsBelongOnlyToHysteria() {
        for (protocol in listOf("trojan", "vless")) {
            assertFalse(CoreCapabilities.fields("xray", protocol).any { it.path.startsWith("streamSettings.finalmask.quicParams") || it.path.startsWith("streamSettings.hysteriaSettings") })
        }
        assertTrue(CoreCapabilities.fields("xray", "vless").any { it.path == "streamSettings.finalmask" })
        assertTrue(CoreCapabilities.fields("xray", "hysteria2").any { it.path == "streamSettings.hysteriaSettings.udpIdleTimeout" })
    }
    private val vless = "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?type=tcp&security=tls&sni=example.com"
    private fun advanced(json: String, engine: String = "sing-box", uri: String = vless) =
        VpnConfig(uri, engine, VpnOptions().withEngine(engine, NativeOptions.parse(json)))
    private fun fixture(name: String, p: VpnConfig) {
        p.validate()
        val config = if (p.usesXray) XrayVpnConfig.generate(p,"127.0.0.1:21089","test","test",false)
            else SingBoxConfig.generate(p,"127.0.0.1:21089","test","test")
        File("build/core-capabilities-fixtures").mkdirs()
        File("build/core-capabilities-fixtures/$name.json").writeText(config.toString())
    }
    @Test fun oldAutoProfilesUseAssignedCoreWithoutChangingUriOrOptions() {
        val old=Gson().fromJson("""{"uri":"$vless","engine":"auto"}""",VpnConfig::class.java)
        assertNull(old.options); assertTrue(old.isValid()); assertTrue(old.usesXray)
        assertEquals(vless, old.sanitize().uri)
        assertTrue(VpnConfig(vless.replace("type=tcp","type=xhttp")).usesXray)
        assertEquals(VpnConfig(vless).outbound(),old.outbound())
    }
    @Test fun fieldsSurviveJsonAndEngineSwitch() {
        val options=VpnOptions(1,NativeOptions.parse("""{"tcp_fast_open":true,"future_field":{"nested":7}}"""),NativeOptions.parse("""{"mux":{"enabled":false}}"""))
        val restored=Gson().fromJson(Gson().toJson(VpnConfig(vless,"xray",options)),VpnConfig::class.java)
        assertEquals(options.singBox,restored.options?.singBox)
        assertEquals(options.xray,restored.options?.xray)
        assertEquals(options.singBox,restored.copy(engine="sing-box").options?.singBox)
    }
    @Test fun managedFieldsAndIncompatibleMuxAreRejected() {
        for (json in listOf("""{"type":"direct"}""","""{"detour":"direct"}""","""{"tls":null}""")) assertFalse(advanced(json).isValid())
        assertFalse(advanced("""{"multiplex":{"enabled":true}}""",uri=vless+"&flow=xtls-rprx-vision").isValid())
        assertFalse(advanced("""{"multiplex":{"enabled":true}}""",uri=vless.replace("tcp","xhttp")).isValid())
        assertFalse(advanced("""{"transport":{"download":{"server":"example.com"}}}""").isValid())
    }
    @Test fun fieldsUseDeepMergeAndDeletion() {
        val base=NativeOptions.parse("""{"tls":{"enabled":true,"server_name":"example.com"}}""")
        val updated=NativeOptions.put(base,"tls.handshake_timeout",CoreField("", "", "", "duration").parse("5s"))
        assertEquals("example.com",NativeOptions.get(updated,"tls.server_name")?.asString)
        assertEquals(base,NativeOptions.put(updated,"tls.handshake_timeout",null))
        assertThrows(IllegalArgumentException::class.java) { CoreField("x","x","x","int").parse("1.5") }
    }
    @Test fun nativeFixturesCoverNewSettings() {
        fixture("extended-httpupgrade",advanced("{}",uri=vless.replace("type=tcp","type=httpupgrade")+"&path=%2Ftuna&host=example.com"))
        fixture("xray-httpupgrade",advanced("{}","xray",vless.replace("type=tcp","type=httpupgrade")+"&path=%2Ftuna&host=example.com"))
        fixture("extended-tls",advanced("""{"connect_timeout":"8s","tcp_fast_open":true,"tcp_keep_alive":"30s","udp_fragment":false,"tls":{"min_version":"1.2","max_version":"1.3","handshake_timeout":"6s","fragment":true,"record_fragment":true}}"""))
        fixture("extended-mux",advanced("""{"multiplex":{"enabled":true,"protocol":"smux","max_connections":4,"max_streams":8,"padding":true}}"""))
        fixture("extended-gecko",advanced("""{"obfs":{"type":"gecko","password":"test-password","min_packet_size":64,"max_packet_size":256},"bbr_profile":"standard","up_mbps":20,"down_mbps":100,"stream_receive_window":1048576,"connection_receive_window":4194304,"initial_packet_size":1280,"idle_timeout":"30s","keep_alive_period":"10s","disable_path_mtu_discovery":true}""",uri="hysteria2://test@192.0.2.1:443?sni=example.com"))
        fixture("extended-download",advanced("""{"transport":{"download":{"server":"192.0.2.2","server_port":443,"path":"/down","x_padding_bytes":"100-1000","tls":{"enabled":true,"server_name":"example.com"}}}}""",uri=vless.replace("type=tcp","type=xhttp")))
        fixture("extended-realm",advanced("""{"realm":{"server_url":"https://example.com","realm_id":"test","stun_servers":["stun.l.google.com:19302"],"ip_version":4,"port_mapping":{"enabled":false}}}""",uri="hysteria2://test@192.0.2.1:443?sni=example.com"))
        fixture("xray-quic",advanced("""{"streamSettings":{"hysteriaSettings":{"udpIdleTimeout":60},"finalmask":{"quicParams":{"congestion":"brutal","brutalUp":"20mbps","brutalDown":"100mbps","maxIdleTimeout":30,"keepAlivePeriod":10,"maxIncomingStreams":100,"disablePathMTUDiscovery":true}}}}""","xray","hysteria2://test@192.0.2.1:443?sni=example.com"))
        fixture("xray-tls",advanced("""{"streamSettings":{"sockopt":{"tcpFastOpen":true,"tcpKeepAliveIdle":30,"tcpKeepAliveInterval":10},"tlsSettings":{"minVersion":"1.2","maxVersion":"1.3","alpn":["h2","http/1.1"]}}}""","xray"))
        fixture("xray-mux",advanced("""{"mux":{"enabled":true,"concurrency":4,"xudpConcurrency":8}}""","xray"))
        fixture("xray-download",advanced("""{"streamSettings":{"xhttpSettings":{"downloadSettings":{"address":"192.0.2.2","port":443,"network":"xhttp","security":"tls","tlsSettings":{"serverName":"example.com"},"xhttpSettings":{"path":"/down"}}}}}""","xray",vless.replace("type=tcp","type=xhttp")))
    }
}
