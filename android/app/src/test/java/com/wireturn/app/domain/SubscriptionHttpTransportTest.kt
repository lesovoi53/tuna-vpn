package com.wireturn.app.domain

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class SubscriptionHttpTransportTest {
    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))
    private class Connection(val failure: Exception? = null, val status: Int = 200) : HttpURLConnection(URL("https://fixture.invalid")) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = true
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
    }

    @Test fun tunnelRequiredNeverFallsBackWhenProxyFails() {
        val routes = mutableListOf<Proxy>()
        assertThrows(IOException::class.java) {
            SubscriptionHttpTransport.open("https://fixture.invalid", proxy, true, "fixture") {
                routes += it
                Connection(if (it == proxy) IOException("proxy unavailable") else null)
            }
        }
        assertEquals(listOf(proxy), routes)
    }
    @Test fun manualRefreshWithTunnelRequiredDoesNotOpenAnythingWhenTunnelIsDown() {
        var opened = false
        assertThrows(IOException::class.java) {
            SubscriptionHttpTransport.open("https://fixture.invalid", Proxy.NO_PROXY, true, "fixture") {
                opened = true; Connection()
            }
        }
        assertFalse(opened)
    }
    @Test fun failedProxyConnectionIsClosedBeforeAnAllowedDirectRetry() {
        val failed = Connection(IOException("proxy failed"))
        val direct = Connection()
        val routes = mutableListOf<Proxy>()
        val result = SubscriptionHttpTransport.open("https://fixture.invalid", proxy, false, "fixture") {
            routes += it
            if (it == proxy) failed else {
                assertTrue("Failed attempt leaked its connection", failed.closed)
                direct
            }
        }
        assertSame(direct, result)
        assertEquals(listOf(proxy, Proxy.NO_PROXY), routes)
        assertFalse(direct.closed)
    }
    @Test fun cancellationClosesAttemptAndNeverRetriesDirect() {
        val failed = Connection(CancellationException("cancel"))
        var calls = 0
        assertThrows(CancellationException::class.java) {
            SubscriptionHttpTransport.open("https://fixture.invalid", proxy, false, "fixture") {
                calls++; failed
            }
        }
        assertEquals(1, calls)
        assertTrue(failed.closed)
    }
    @Test fun reachableHttpFailureIsReturnedToImporterWithoutAnotherRoute() {
        val unavailable = Connection(status = 503)
        var calls = 0
        assertSame(unavailable, SubscriptionHttpTransport.open("https://fixture.invalid", proxy, false, "fixture") {
            calls++; unavailable
        })
        assertEquals(1, calls)
        assertEquals(10000, unavailable.connectTimeout)
        assertEquals("fixture", unavailable.getRequestProperty("User-Agent"))
    }
}
