package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Loopback SOCKS endpoint: no public network or production TLS trust changes. */
class ProxyHttpTextTest {
    private fun fetch(response: String, maxChars: Int = 8192, slowChunks: Boolean = false, cancel: Boolean = false): String {
        val executor = Executors.newSingleThreadExecutor()
        val readingBody = CompletableDeferred<Unit>()
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 3000
            try {
                executor.submit {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        check(input.readUnsignedByte() == 5)
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        socket.getOutputStream().write(byteArrayOf(5, 0))
                        input.readFully(ByteArray(3))
                        val type = input.readUnsignedByte()
                        input.readFully(ByteArray(when(type) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS type") }))
                        input.readUnsignedShort()
                        socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                        val reader = input.bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        readingBody.complete(Unit)
                        socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                        if (slowChunks) {
                            repeat(6) {
                                socket.getOutputStream().write("1\r\nx\r\n".toByteArray())
                                socket.getOutputStream().flush()
                                Thread.sleep(350)
                            }
                            socket.getOutputStream().write("0\r\n\r\n".toByteArray())
                        }
                    }
                }
                runBlocking {
                    val request = async { ProxyHttpText.fetch("http://127.0.0.1:80/fixture", Proxy(Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", listener.localPort)), maxChars = maxChars, totalTimeoutMs = if (cancel) 5000 else 1000) }
                    if (cancel) {
                        withTimeout(3000) { readingBody.await() }
                        delay(100)
                        val started = System.nanoTime()
                        request.cancelAndJoin()
                        assertTrue("Cancelled diagnostic kept reading", (System.nanoTime() - started) / 1_000_000 < 1200)
                        "cancelled"
                    } else request.await()
                }
            } finally { executor.shutdownNow(); executor.awaitTermination(4, TimeUnit.SECONDS) }
        }
    }

    private fun response(body: String, status: String = "200 OK") =
        "HTTP/1.1 $status\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"

    @Test fun normalCountryAndExactSizeResponsesAreAccepted() {
        assertEquals("DE\n", fetch(response("DE\n")))
        assertEquals("x".repeat(8192), fetch(response("x".repeat(8192))))
    }
    @Test fun largeResponseCannotBeBufferedWithoutLimit() {
        assertThrows(IllegalArgumentException::class.java) { fetch(response("x".repeat(65536))) }
    }
    @Test fun redirectBodyCannotBeUsedAsTheExitCountry() {
        assertThrows(IllegalArgumentException::class.java) { fetch(response("DE", "302 Found")) }
    }
    @Test fun unicodeLimitIsAppliedToDecodedCharacters() {
        assertEquals("я".repeat(4), fetch(response("я".repeat(4)), 4))
        assertThrows(IllegalArgumentException::class.java) { fetch(response("я".repeat(5)), 4) }
    }
    @Test fun noActiveProxyNeverFallsBackToDirect() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ProxyHttpText.fetch("http://127.0.0.1/", Proxy.NO_PROXY) } }
    }
    @Test fun trickledResponseCannotExtendTheTotalDeadline() {
        val started = System.nanoTime()
        assertThrows(TimeoutCancellationException::class.java) {
            fetch("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", slowChunks = true)
        }
        assertTrue("Timed-out HTTP read kept its coroutine blocked", (System.nanoTime() - started) / 1_000_000 < 1900)
    }
    @Test fun leavingHomeCancelsAnInFlightDiagnosticBeforeItsTimeout() {
        assertEquals("cancelled", fetch("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", slowChunks = true, cancel = true))
    }
}
