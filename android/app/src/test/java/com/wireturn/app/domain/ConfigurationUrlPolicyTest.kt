package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class ConfigurationUrlPolicyTest {
    @Test fun requiresHttpsForPublicLanAndLoopbackAndEveryRedirect() {
        for (url in listOf("https://127.0.0.1/sub", "https://192.168.1.2/sub", "https://example.com/sub"))
            ConfigurationUrlPolicy.validate(URL(url))
        for (host in listOf("example.com", "localhost", "127.0.0.1", "192.168.1.2", "10.0.0.1", "[::1]")) {
            assertThrows(ConfigurationUrlRejected::class.java) { ConfigurationUrlPolicy.validate(URL("http://$host/sub")) }
            assertThrows(ConfigurationUrlRejected::class.java) { ConfigurationUrlPolicy.redirect(URL("https://example.com/sub"), "http://$host/token") }
        }
        assertEquals(URL("https://example.com/next"), ConfigurationUrlPolicy.redirect(URL("https://example.com/sub"), "/next"))
    }
    @Test fun cleartextIsRejectedBeforeOpeningEvenALocalSocket() {
        var opened = false
        assertThrows(ConfigurationUrlRejected::class.java) {
            SubscriptionHttpTransport.openChain("http://127.0.0.1/sub", Proxy.NO_PROXY, false, "test") { _, _ ->
                opened = true
                error("Unexpected connection")
            }
        }
        assertFalse(opened)
        val issue = ConfigurationUrlPolicy.rejectedStatus().report.rejected.single()
        assertTrue(issue.reason.contains("HTTPS"))
        assertTrue(issue.reason.contains("Сохранённые профили не изменены"))
    }
    @Test fun deniedRedirectNeverOpensTargetAndClosesSource() {
        var calls = 0
        var closed = false
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionHttpTransport.openChain("https://192.168.1.2/sub", Proxy.NO_PROXY, false, "test") { url, _ ->
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
