package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wireturn.app.kernel.WebdavRuntimeConfig
import org.junit.Assert.*
import org.junit.Test

class WebdavConfigTest {
    private val base = WebdavConfig(webdav = "https://storage.invalid/dav/a%20b", login = "u+ser%@", password = "p:ass%3A+@\"\\\n\r\tЮникод\u0001")
    @Test fun credentialsRoundTripExactlyOnce() {
        val cfg = base.copy(encrypt = true, dns = "tcp://127.0.0.1:5353", backends = listOf(WebdavBackend(url = "http://backup.invalid/dav", login = "a:b", password = "c:d%25")))
        assertEquals(cfg, WebdavConfig.parse(cfg.toUri("Тест профиля")))
        assertEquals("p:a%3A+b", WebdavConfig.parse("webdavs://u:p%3Aa%253A+b@storage.invalid")!!.password)
    }
    @Test fun unrelatedImportDoesNotInheritSecretsOrOverrides() {
        val old = base.copy(encrypt = true, dns = "1.1.1.1", backends = listOf(WebdavBackend(url = "http://old.invalid", login = "old", password = "old")))
        val fresh = WebdavConfig.parse("webdavs://new:pass@new.invalid", old)!!
        assertTrue(fresh.backends.isEmpty()); assertEquals("", fresh.dns); assertFalse(fresh.encrypt)
    }
    @Test fun validatesRangesAndEveryBackend() {
        listOf(base.copy(pollMin="2s",pollMax="1s"), base.copy(timeout="0s"),base.copy(readMin="9",readMax="8"),base.copy(puts="0"),base.copy(chunkSize="999999999999"),base.copy(backends=listOf(WebdavBackend())),base.copy(dns="tcp://dns.invalid:53"),base.copy(webdav="https://user:pass@storage.invalid"),base.copy(webdav="https://storage.invalid/?token=secret")).forEach { assertFalse(it.isValid()) }
        assertTrue(base.copy(timeout="1m30s",coalesce="0",pollMin="1ms").isValid())
    }
    @Test fun rejectsAmbiguousOrOversizedLinks() {
        listOf("webdavs://u:p@host.invalid?puts=2&puts=3", "webdavs://u:p@host.invalid?enc=yes", "webdavs://u:p@host.invalid?unknown=1", "webdavs://u:p@host.invalid?backend=broken", "webdavs://u:p@host.invalid?puts="+"1".repeat(131072)).forEach { assertNull(WebdavConfig.parse(it)) }
    }
    @Test fun legacyNullsAreSanitizedAndInvalidBackendNotLost() {
        val cfg=Gson().fromJson("""{"webdav":"https://storage.invalid","login":"u","password":"p","backends":[null],"timeout":null}""",WebdavConfig::class.java).fillDefaults()
        assertEquals("60s",cfg.timeout);assertEquals(1,cfg.backends.size);assertFalse(cfg.isValid())
    }
    @Test fun safeRuntimeJSONKeepsCredentialsAndTypes() {
        val raw=WebdavRuntimeConfig.build(base,"127.0.0.1:1080","u","p","tcp://127.0.0.1:5353")
        val json=JsonParser.parseString(raw).asJsonObject
        assertEquals(base.password,json.getAsJsonArray("backends")[0].asJsonObject["password"].asString)
        assertEquals("tcp://127.0.0.1:5353",json["dns"].asString)
        assertEquals(131071,json.getAsJsonObject("tuning")["chunk-size"].asInt)
        assertEquals("u",json["socks-user"].asString)
        val own=JsonParser.parseString(WebdavRuntimeConfig.build(base.copy(dns="9.9.9.9"),"127.0.0.1:1080","u","p","tcp://1.1.1.1:53")).asJsonObject
        assertEquals("udp://9.9.9.9:53",own["dns"].asString)
        val os=JsonParser.parseString(WebdavRuntimeConfig.build(base,"127.0.0.1:1080","u","p","")).asJsonObject
        assertFalse(os.has("dns"))
    }
    @Test fun chainValidationOnlyAppliesWhenChainIsActive() {
        WebdavRuntimeConfig.validateChain(false, "hysteria2://p@host.invalid:443")
        WebdavRuntimeConfig.validateChain(true,"vless://u@host.invalid:443?type=xhttp")
        for (link in listOf("hysteria2://p@host.invalid:443","tuic://u:p@host.invalid:443","vless://u@host.invalid:443?type=quic","vless://u@host.invalid:443?type=unknown")) {
            assertTrue(runCatching { WebdavRuntimeConfig.validateChain(true,link) }.isFailure)
        }
    }
    @Test fun fullProfilePersistenceRestoresWebdavAndAuthentication() {
        val gson = com.google.gson.GsonBuilder().registerTypeAdapter(com.wireturn.app.data.KernelConfig::class.java, com.wireturn.app.data.KernelConfigAdapter()).create()
        val client = com.wireturn.app.data.ClientConfig(kernelConfig = com.wireturn.app.data.KernelConfig.Webdav(base)).fillDefaults()
        assertTrue(client.isValid); assertTrue(client.isSocksAuthEnabled)
        assertEquals(client, gson.fromJson(gson.toJson(client), com.wireturn.app.data.ClientConfig::class.java))
        assertEquals(client.kernelConfig, com.wireturn.app.data.KernelConfig.parseUri(base.toUri()))
    }
    @Test fun logsNeverReturnUntrustedURLsOrCredentials() {
        val secret="https://secret-user:secret-pass@host.invalid"
        assertNull(WebdavRuntimeConfig.safeLog(secret))
        assertFalse(WebdavRuntimeConfig.safeLog("fatal $secret")!!.contains("secret"))
        assertFalse(WebdavRuntimeConfig.safeLog("server connected $secret")!!.contains("secret"))
    }
    @Test fun errorsKeepTheirCategoryWithoutEchoingPrivateData() {
        val cases = mapOf("dial tcp: lookup private.invalid: no such host" to "DNS",
            "dial tcp 192.0.2.1:443: connect: connection refused" to "TCP",
            "tls: failed to verify certificate: x509: certificate expired" to "TLS",
            "context deadline exceeded" to "таймаут", "PROPFIND private-path: 401 Unauthorized" to "HTTP 401",
            "PROPFIND private-path: 503 Service Unavailable" to "HTTP 503")
        for ((error, category) in cases) {
            val safe = WebdavRuntimeConfig.safeLog("WebDAV backend https://secret-user:secret-pass@private.invalid: connection failed: $error")!!
            assertTrue(safe, safe.contains(category))
            for (secret in listOf("secret-user", "secret-pass", "private", "192.0.2.1")) assertFalse(safe.contains(secret))
        }
    }
}
