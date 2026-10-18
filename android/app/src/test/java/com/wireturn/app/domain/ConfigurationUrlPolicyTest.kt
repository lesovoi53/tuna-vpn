package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class ConfigurationUrlPolicyTest {
    @Test fun permitsLocalHttpButRejectsEveryDowngradeHop() {
        for (url in listOf("http://127.0.0.1/sub", "http://192.168.1.2/sub", "https://example.com/sub"))
            ConfigurationUrlPolicy.validate(URL(url))
        assertThrows(IllegalArgumentException::class.java) { ConfigurationUrlPolicy.validate(URL("http://example.com/sub")) }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationUrlPolicy.redirect(URL("http://192.168.1.2/sub"), "http://example.com/token") }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationUrlPolicy.redirect(URL("https://example.com/sub"), "http://192.168.1.2/sub") }
    }
    @Test fun deniedRedirectNeverOpensTargetAndClosesSource() {
        var calls = 0
        var closed = false
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionHttpTransport.openChain("http://192.168.1.2/sub", Proxy.NO_PROXY, false, "test") { url, _ ->
                calls++
                object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() { closed = true }
                    override fun usingProxy() = false
                    override fun getResponseCode() = 302
                    override fun getHeaderField(name: String) = "http://example.com/token"
                }
            }
        }
        assertEquals(1, calls)
        assertTrue(closed)
    }
}
