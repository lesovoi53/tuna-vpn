package com.wireturn.app.data.kernel

import com.google.gson.JsonParser
import com.wireturn.app.kernel.WebdavRuntimeConfig
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

/** Reserved storage names; parsing and runtime JSON generation never contact a backend. */
class WebdavAdversarialTest {
    private val base = WebdavConfig(webdav = "https://primary.invalid/dav/%252F", login = "user", password = "password")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun runtime(config: WebdavConfig) = WebdavRuntimeConfig.build(config, "127.0.0.1:1080", "local", "secret", "")

    @Test fun maximumBackendListKeepsOrderAndDecodedCredentialsInNativeJson() {
        val backends = (1..WebdavConfig.MAX_BACKENDS).map { index ->
            WebdavBackend(url = "https://backup$index.invalid:8443/dav/a%20b%252F",
                login = "логин:$index+%2F", password = "пароль:$index@+%2F/=?&#")
        }
        val original = base.copy(backends = backends, encrypt = true)
        val imported = WebdavConfig.parse(original.toUri("Тест + %"))!!
        assertEquals(original, imported)
        val json = JsonParser.parseString(runtime(imported)).asJsonObject
        val native = json.getAsJsonArray("backends")
        val expected = listOf(WebdavBackend(url = base.webdav, login = base.login, password = base.password)) + backends
        assertEquals(expected.size, native.size())
        for ((index, backend) in expected.withIndex()) {
            val actual = native[index].asJsonObject
            assertEquals(backend.url, actual["url"].asString)
            assertEquals(backend.login, actual["login"].asString)
            assertEquals(backend.password, actual["password"].asString)
        }
        assertTrue(json["enc"].asBoolean)
    }

    @Test fun invalidConstructedValuesCannotReachRuntimeOrUriExport() {
        val validBackend = WebdavBackend(url = "https://backup.invalid", login = "user", password = "pass")
        val invalid = listOf(
            base.copy(backends = List(WebdavConfig.MAX_BACKENDS + 1) { validBackend }),
            base.copy(backends = listOf(validBackend.copy(password = " "))),
            base.copy(backends = listOf(validBackend.copy(label = "x".repeat(201)))),
            base.copy(login = "x".repeat(2049)), base.copy(password = "x".repeat(8193)),
            base.copy(webdav = "https://primary.invalid:65536/dav"),
            base.copy(timeout = "NaNs"), base.copy(timeout = "600001ms"),
            base.copy(pollMin = "0ms"), base.copy(coalesce = "1.001s"),
            base.copy(chunkSize = "4194305"), base.copy(chunkSize = "1024.5"),
            base.copy(puts = "33"), base.copy(readMin = "4", readMax = "3")
        )
        for ((index, config) in invalid.withIndex()) {
            assertFalse("Invalid fixture $index", config.isValid())
            assertThrows("Export fixture $index", IllegalArgumentException::class.java) { config.toUri() }
            assertThrows("Runtime fixture $index", IllegalArgumentException::class.java) { runtime(config) }
        }
        assertNotNull(WebdavConfig.parse(base.copy(timeout = "10m", pollMin = "1ms", pollMax = "60s",
            coalesce = "1s", chunkSize = "4194304", puts = "32", readMin = "32", readMax = "32").toUri()))
    }

    @Test fun nestedBackendOptionsAndEncodedDuplicateKeysCannotChangePrimarySettings() {
        val uri = base.toUri()
        for (nested in listOf(
            "webdavs://u:p@backup.invalid/dav?timeout=1s",
            "webdavs://u:p@backup.invalid/dav#hidden",
            "webdavs://u:p@backup.invalid:0/dav",
            "webdavs://u:p%ZZ@backup.invalid/dav"
        )) assertNull(nested, WebdavConfig.parse("$uri&backend=${encode(nested)}"))
        assertNull(WebdavConfig.parse("$uri&%74imeout=1s"))
        assertNull(WebdavConfig.parse(uri + "&backend=" + encode("webdavs://u:p@backup.invalid") +
            "&backend=" + encode("webdavs://u:@broken.invalid")))
    }

}
