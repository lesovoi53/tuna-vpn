package com.wireturn.app.domain

import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
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
                        factory = { route -> (url.openConnection(route) as javax.net.ssl.HttpsURLConnection).apply {
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
}
