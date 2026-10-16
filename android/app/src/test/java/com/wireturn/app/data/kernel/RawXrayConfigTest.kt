package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.wireturn.app.kernel.RawXrayConfig
import com.wireturn.app.kernel.XrayVpnConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RawXrayConfigTest {
    private val raw = """{
      "remarks":"Native balance",
      "inbounds":[{"protocol":"mixed","tag":"local","listen":"0.0.0.0","port":1080},{"protocol":"http","tag":"web","port":8080}],
      "outbounds":[
        {"protocol":"vless","tag":"edge1","settings":{"vnext":[{"address":"vpn.example","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}]}]},"streamSettings":{"network":"xhttp","security":"reality","realitySettings":{"serverName":"example.org","fingerprint":"chrome","publicKey":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"},"xhttpSettings":{"path":"/rpc","mode":"auto"}}},
        {"protocol":"freedom","tag":"edge2","settings":{}},{"protocol":"blackhole","tag":"block","settings":{}}],
      "dns":{"servers":["1.1.1.1"],"tag":"resolver","queryStrategy":"UseIPv4"},
      "routing":{"domainStrategy":"AsIs","balancers":[{"tag":"auto","selector":["edge"],"strategy":{"type":"random"}}],"rules":[{"type":"field","inboundTag":["local","web"],"domain":["domain:example.net"],"outboundTag":"block"},{"type":"field","network":"tcp,udp","balancerTag":"auto"}]},
      "observatory":{"subjectSelector":["edge"],"probeURL":"https://example.org/","probeInterval":"1m"},
      "policy":{"levels":{"0":{"connIdle":300}},"system":{"statsOutboundUplink":true}},"stats":{},"log":{"loglevel":"debug"}
    }""".trimIndent()

    @Test fun rawProfileKeepsOriginalAndUsesNativeCore() {
        val profile = VpnConfig(rawXrayJson = raw).sanitize()
        profile.validate()
        assertEquals(raw, profile.rawXrayJson)
        assertEquals("", profile.uri)
        assertEquals("xray-json", profile.protocol)
        assertEquals("xray", profile.selectedEngine)
        assertTrue(profile.usesXray)
        assertTrue(profile.isXrayBalancer)
        assertEquals("Native balance", profile.displayName)
        assertEquals(profile, Gson().fromJson(Gson().toJson(profile), VpnConfig::class.java))
        assertFalse(Gson().fromJson("{\"uri\":\"vless://example\"}", VpnConfig::class.java).isRawXray)
    }

    @Test fun runtimeRetainsPolicyButOwnsListenerAndLog() {
        val profile = VpnConfig(rawXrayJson = raw)
        val wrapper = XrayVpnConfig.generate(profile, "127.0.0.1:2081", "local-user", "local-pass", false)
        val runtime = wrapper.getAsJsonObject("raw_xray")
        val original = RawXrayConfig.parse(raw)
        for (field in listOf("outbounds", "dns", "observatory", "policy", "stats")) assertEquals(field, original[field], runtime[field])
        assertEquals(original.getAsJsonObject("routing")["balancers"], runtime.getAsJsonObject("routing")["balancers"])
        assertEquals("tuna-json-in", runtime.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("inboundTag")[0].asString)
        assertEquals(1, runtime.getAsJsonArray("inbounds").size())
        val inbound = runtime.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("127.0.0.1", inbound["listen"].asString)
        assertEquals("password", inbound.getAsJsonObject("settings")["auth"].asString)
        assertEquals("local-user", inbound.getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject["user"].asString)
        assertEquals("warning", runtime.getAsJsonObject("log")["loglevel"].asString)
        assertFalse(runtime.has("remarks"))
        assertFalse(wrapper.has("link"))
        assertEquals(raw, profile.rawXrayJson)
    }

    @Test fun rejectsUnsafeCapabilitiesAndAmbiguousListeners() {
        fun rejects(change: (JsonObject) -> Unit) {
            val root = RawXrayConfig.parse(raw).also(change)
            assertThrows(IllegalArgumentException::class.java) { VpnConfig(rawXrayJson = root.toString()).validate() }
        }
        rejects { it.add("api", JsonObject()) }
        rejects { it.getAsJsonObject("log").addProperty("error", "/private/overwrite") }
        rejects { it.getAsJsonArray("outbounds")[0].asJsonObject.addProperty("certificateFile", "/private/read") }
        rejects { it.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("protocol", "dokodemo-door") }
        rejects { it.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("inboundTag").remove(1) }
        rejects { it.getAsJsonObject("routing").getAsJsonArray("balancers")[0].asJsonObject.addProperty("fallbackTag", "missing") }
        assertThrows(IllegalArgumentException::class.java) { RawXrayConfig.parse("{\"outbounds\":[],\"outbounds\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { RawXrayConfig.parseContainer("[{\"a\":1,\"a\":2}]") }
        assertThrows(IllegalArgumentException::class.java) { RawXrayConfig.parse(raw + "{}") }
        assertThrows(IllegalArgumentException::class.java) { XrayVpnConfig.generate(VpnConfig(rawXrayJson = raw), "0.0.0.0:2081", "u", "p", false) }
        assertThrows(IllegalArgumentException::class.java) { XrayVpnConfig.generate(VpnConfig(rawXrayJson = raw), "127.0.0.1:2081", "", "", false) }
        assertFalse(VpnConfig(rawXrayJson = "invalid").isXrayBalancer)
    }

    @Test fun doesNotRewriteNativeFragmentationAndRequiresInstalledGeoAssets() {
        val profile = VpnConfig(rawXrayJson = raw)
        assertEquals(profile, VpnFragmentation.applyDefault(profile, VpnFragmentation("both", "3", "0")))
        assertThrows(IllegalArgumentException::class.java) { profile.copy(options = VpnOptions(xray = JsonObject().apply { addProperty("mux", true) })).validate() }
        assertEquals(emptySet<String>(), RawXrayConfig.requiredGeoAssets(profile))
        val geo = profile.copy(rawXrayJson = raw.replace("domain:example.net", "geosite:category-test"))
        assertEquals(setOf("geosite.dat"), RawXrayConfig.requiredGeoAssets(geo))
        assertThrows(IllegalArgumentException::class.java) { RawXrayConfig.validateAssets(geo, null) }
    }

    @Test fun exportsSyntheticFixtureForPinnedNativeParser() {
        val directory = File("build/raw-xray-test-configs").apply { mkdirs() }
        File(directory, "balancer.json").writeText(XrayVpnConfig.generate(VpnConfig(rawXrayJson = raw), "127.0.0.1:2081", "test", "test", false).toString())
    }
}
