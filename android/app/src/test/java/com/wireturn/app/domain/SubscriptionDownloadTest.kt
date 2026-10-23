package com.wireturn.app.domain

import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDownloadTest {
    private fun fixture(slowBody: Boolean = false, delayedHeaders: Boolean = false, cancel: Boolean = false): SubscriptionResponse? {
        val executor = Executors.newSingleThreadExecutor()
        val receivedRequest = CompletableDeferred<Unit>()
        return SyntheticHttps.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 4000
            try {
                executor.submit {
                    listener.accept().use { socket ->
                        socket.soTimeout = 4000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        receivedRequest.complete(Unit)
                        if (delayedHeaders) Thread.sleep(2500)
                        val output = socket.getOutputStream()
                        if (slowBody) {
                            output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                            repeat(7) { output.write("1\r\nx\r\n".toByteArray()); output.flush(); Thread.sleep(250) }
                            output.write("0\r\n\r\n".toByteArray())
                        } else output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nProfile-Title: Fixture\r\nConnection: close\r\n\r\n{}".toByteArray())
                    }
                }
                runBlocking {
                    val url = java.net.URL("https://127.0.0.1:${listener.localPort}/")
                    val request = async { SubscriptionDownload.fetch(url.toString(), Proxy.NO_PROXY, false, "fixture",
                        totalTimeoutMs = if (cancel) 5000 else if (slowBody) 1000 else 3000,
                        factory = { address, route -> (address.openConnection(route) as javax.net.ssl.HttpsURLConnection).apply {
                            sslSocketFactory = SyntheticHttps.context.socketFactory
                        } }) }
                    if (cancel) {
                        withTimeout(3000) { receivedRequest.await() }
                        val start = System.nanoTime(); request.cancelAndJoin()
                        assertTrue("Cancel waited for response headers", (System.nanoTime() - start) / 1_000_000 < 1200)
                        null
                    } else request.await()
                }
            } finally { executor.shutdownNow(); executor.awaitTermination(4, TimeUnit.SECONDS) }
        }
    }

    @Test fun successfulDownloadPreservesBodyAndCaseInsensitiveHeaders() {
        val response = fixture()!!
        assertEquals(200, response.code)
        assertEquals("{}", response.bytes.toString(Charsets.UTF_8))
        assertEquals("Fixture", response.header("profile-title"))
    }

    @Test fun trickledResponseMustObserveAnOverallDeadline() {
        assertThrows(SocketTimeoutException::class.java) { fixture(slowBody = true) }
    }

    @Test fun cancellationMustCloseTheConnectionBeforeHeadersArrive() {
        fixture(delayedHeaders = true, cancel = true)
    }

    private class RedirectConnection(address: URL) : HttpURLConnection(address) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = 302
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", true)) "https://redirect.example.invalid/final" else null
    }

    private class BlockingConnection(address: URL, private val blockHeaders: Boolean) : HttpURLConnection(address) {
        val entered = CompletableDeferred<Unit>()
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
        private fun block(): Nothing {
            entered.complete(Unit)
            if (!disconnected.await(4, TimeUnit.SECONDS)) throw IOException("fixture did not observe disconnect")
            throw IOException("fixture disconnected")
        }
        override fun getResponseCode(): Int = if (blockHeaders) block() else 200
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int = block()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = block()
        }
    }

    @Test fun redirectedBodyUsesTheOverallDeadlineAndClosesTheFinalConnection() {
        val first = RedirectConnection(URL("https://initial.example.invalid/start"))
        val last = BlockingConnection(URL("https://redirect.example.invalid/final"), false)
        val calls = AtomicInteger()
        val start = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking {
                SubscriptionDownload.fetch(first.url.toString(), Proxy.NO_PROXY, false, "fixture", totalTimeoutMs = 500,
                    factory = { address, _ ->
                        calls.incrementAndGet()
                        if (address.toExternalForm() == first.url.toExternalForm()) first else {
                            assertEquals(last.url.toExternalForm(), address.toExternalForm())
                            assertTrue(first.closed)
                            last
                        }
                    })
            }
        }
        assertEquals(2, calls.get())
        assertEquals(0L, last.disconnected.count)
        assertTrue("Redirect escaped the overall deadline", (System.nanoTime() - start) / 1_000_000 < 2500)
    }

    @Test fun cancellingRedirectedHeadersClosesFinalConnectionWithoutDirectRetry() = runBlocking {
        val first = RedirectConnection(URL("https://initial.example.invalid/start"))
        val last = BlockingConnection(URL("https://redirect.example.invalid/final"), true)
        val calls = AtomicInteger()
        val proxy = Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080))
        val request = async {
            SubscriptionDownload.fetch(first.url.toString(), proxy, false, "fixture", totalTimeoutMs = 5000,
                factory = { address, route ->
                    assertEquals(proxy, route)
                    calls.incrementAndGet()
                    if (address.toExternalForm() == first.url.toExternalForm()) first else last
                })
        }
        withTimeout(2000) { last.entered.await() }
        val start = System.nanoTime()
        request.cancelAndJoin()
        assertTrue("Cancel waited for redirected headers", (System.nanoTime() - start) / 1_000_000 < 1200)
        assertEquals(2, calls.get())
        assertTrue(first.closed)
        assertEquals(0L, last.disconnected.count)
    }
}
