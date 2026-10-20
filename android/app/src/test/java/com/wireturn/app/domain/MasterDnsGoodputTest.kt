package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/** Real local SOCKS auth + TLS + HTTP. No public DNS/network or global TLS/auth overrides. */
class MasterDnsGoodputTest {
    private val fixtureLimits = MasterDnsGoodputLimits(
        url = "https://127.0.0.1/fixture", maxPayloadBytes = 32, totalTimeoutMs = 3000, socketTimeoutMs = 2000)

    private fun response(body: String, status: String = "200 OK", extra: String = "") =
        "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\n${extra}Connection: close\r\n\r\n$body"

    private fun <T> fixture(
        response: String,
        authMethod: Int = 2,
        authResult: Int = 0,
        trickle: Boolean = false,
        bodyStarted: CompletableDeferred<Unit> = CompletableDeferred(),
        block: suspend (Int, AtomicReference<String>, CountDownLatch) -> T
    ): T {
        val executor = Executors.newSingleThreadExecutor()
        val endpoint = AtomicReference("")
        val closed = CountDownLatch(1)
        val activeSocket = AtomicReference<Socket?>()
        return ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            listener.soTimeout = 4000
            val server = executor.submit {
                try {
                    listener.accept().use { socket ->
                        activeSocket.set(socket)
                        socket.soTimeout = 4000
                        val input = DataInputStream(socket.getInputStream())
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(2, input.readUnsignedByte())
                        socket.getOutputStream().write(byteArrayOf(5, authMethod.toByte()))
                        if (authMethod != 2) return@submit
                        assertEquals(1, input.readUnsignedByte())
                        val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                        val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                        assertEquals("fixture-user", user)
                        assertEquals("fixture-pass", pass)
                        socket.getOutputStream().write(byteArrayOf(1, authResult.toByte()))
                        if (authResult != 0) return@submit
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        assertEquals("Destination must remain unresolved in the app", 3, input.readUnsignedByte())
                        endpoint.set(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII))
                        assertEquals(443, input.readUnsignedShort())
                        socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                        val tls = SyntheticHttps.context.socketFactory.createSocket(socket, "localhost", socket.port, false) as SSLSocket
                        tls.useClientMode = false
                        tls.startHandshake()
                        tls.inputStream.bufferedReader().let { reader ->
                            assertEquals("GET /fixture HTTP/1.1", reader.readLine())
                            while (!reader.readLine().isNullOrEmpty()) { }
                        }
                        tls.outputStream.write(response.toByteArray(Charsets.US_ASCII))
                        tls.outputStream.flush()
                        bodyStarted.complete(Unit)
                        if (trickle) {
                            repeat(30) {
                                Thread.sleep(100)
                                tls.outputStream.write("1\r\nx\r\n".toByteArray())
                                tls.outputStream.flush()
                            }
                        }
                        tls.close()
                    }
                } catch (_: java.io.IOException) {
                    // Expected for deliberate cancellation, TLS rejection and bounded-close tests.
                } catch (_: InterruptedException) {
                    // Fixture teardown interrupts only its own sleeping sender.
                    Thread.currentThread().interrupt()
                } finally { closed.countDown() }
            }
            try {
                runBlocking { block(listener.localPort, endpoint, closed) }
            } finally {
                activeSocket.get()?.close()
                listener.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                server.get() // Surface assertion failures inside the server worker.
            }
        }
    }

    private fun fetch(response: String, limits: MasterDnsGoodputLimits = fixtureLimits): MasterDnsGoodput.Transfer =
        fixture(response) { port, _, _ ->
            MasterDnsGoodput.transfer(port, "fixture-user", "fixture-pass", limits, SyntheticHttps.context.socketFactory)
        }

    @Test fun countsOnlyPayloadBytesAfterRealAuthenticatedSocksAndVerifiedTls() {
        fixture(response("abcdefghijklmnop")) { port, endpoint, _ ->
            val progress = mutableListOf<Long>()
            val result = MasterDnsGoodput.transfer(port, "fixture-user", "fixture-pass", fixtureLimits,
                SyntheticHttps.context.socketFactory, progress::add)
            assertEquals(16L, result.payloadBytes)
            assertTrue(result.elapsedMs > 0)
            assertEquals("127.0.0.1", endpoint.get())
            assertEquals(16L, progress.last())
        }
    }

    @Test fun chunkFramingIsExcludedFromPayloadAndCannotExceedTheCap() {
        val headers = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        assertEquals(8L, fetch(headers + "4\r\nabcd\r\n4\r\nefgh\r\n0\r\n\r\n").payloadBytes)
        assertThrows(IllegalArgumentException::class.java) { fetch(headers + "40\r\n" + "x".repeat(64) + "\r\n0\r\n\r\n") }
    }

    @Test fun oversizedTruncatedCompressedAndRedirectedBodiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { fetch(response("x".repeat(33))) }
        assertThrows(java.io.EOFException::class.java) { fetch("HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nshort") }
        assertThrows(IllegalArgumentException::class.java) { fetch(response("x", extra = "Content-Encoding: gzip\r\n")) }
        assertThrows(IllegalArgumentException::class.java) { fetch(response("x", status = "302 Found", extra = "Location: https://example.invalid/\r\n")) }
        assertThrows(IllegalArgumentException::class.java) { fetch(response("")) }
    }

    @Test fun ambiguousOrUnboundedHttpFramingIsRejected() {
        for (headers in listOf(
            "Content-Length: 5\r\nContent-Length: 5\r\n",
            "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n",
            "Transfer-Encoding: gzip\r\n", "", "Content-Length: invalid\r\n"
        )) {
            assertThrows(IllegalArgumentException::class.java) { fetch("HTTP/1.1 200 OK\r\n$headers\r\nhello") }
        }
    }

    @Test fun unauthenticatedAndWrongPasswordSocksCannotFallBack() {
        for ((method, result) in listOf(0 to 0, 2 to 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                fixture(response("x"), authMethod = method, authResult = result) { port, _, _ ->
                    MasterDnsGoodput.transfer(port, "fixture-user", "fixture-pass", fixtureLimits, SyntheticHttps.context.socketFactory)
                }
            }
        }
    }

    @Test fun certificateTrustAndHostnameChecksRemainEnabled() {
        assertThrows(javax.net.ssl.SSLException::class.java) {
            fixture(response("x")) { port, _, _ ->
                MasterDnsGoodput.transfer(port, "fixture-user", "fixture-pass", fixtureLimits)
            }
        }
        assertThrows(javax.net.ssl.SSLException::class.java) {
            fixture(response("x")) { port, endpoint, _ ->
                try {
                    MasterDnsGoodput.transfer(port, "fixture-user", "fixture-pass",
                        fixtureLimits.copy(url = "https://wrong.example.invalid/fixture"), SyntheticHttps.context.socketFactory)
                } finally { assertEquals("wrong.example.invalid", endpoint.get()) }
            }
        }
    }

    @Test fun slowBodyCannotExtendTotalDeadlineAndTimeoutClosesSocket() {
        val begin = System.nanoTime()
        assertThrows(TimeoutCancellationException::class.java) {
            fixture("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", trickle = true) { port, _, closed ->
                try {
                    MasterDnsGoodput.transfer(port, "fixture-user", "fixture-pass",
                        fixtureLimits.copy(totalTimeoutMs = 500), SyntheticHttps.context.socketFactory)
                } finally { assertTrue("Socket remained open after timeout", closed.await(1, TimeUnit.SECONDS)) }
            }
        }
        assertTrue((System.nanoTime() - begin) / 1_000_000 < 2000)
    }

    @Test fun cancellationInterruptsBlockedPayloadReadAndClosesSocket() {
        val started = CompletableDeferred<Unit>()
        fixture("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", trickle = true, bodyStarted = started) { port, _, closed ->
            coroutineScope {
                val job = launch {
                    MasterDnsGoodput.transfer(port, "fixture-user", "fixture-pass", fixtureLimits, SyntheticHttps.context.socketFactory)
                }
                withTimeout(2000) { started.await() }
                val begin = System.nanoTime()
                job.cancelAndJoin()
                assertTrue((System.nanoTime() - begin) / 1_000_000 < 700)
                assertTrue(closed.await(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun rejectsUnsafeUrlsAndUnboundedConfigurationBeforeOpeningSockets() {
        for (url in listOf("http://example.invalid/", "https://user:password@example.invalid/",
            "https://example.invalid/#fragment", "https://example.invalid/\r\nHeader: injected", "https:///path")) {
            assertThrows(IllegalArgumentException::class.java) { MasterDnsGoodputLimits(url = url) }
        }
        assertThrows(IllegalArgumentException::class.java) { fixtureLimits.copy(maxPayloadBytes = 262145) }
        assertThrows(IllegalArgumentException::class.java) { fixtureLimits.copy(totalTimeoutMs = 210001) }
        assertThrows(IllegalArgumentException::class.java) { fixtureLimits.copy(maxWireBytes = 16L * 1024 * 1024 + 1) }
    }

    @Test fun nativePolicyRejectsSecretsUnknownFieldsAndCollapsedResolverPools() {
        val keys = listOf("resolverCount", "strategy", "packetDuplication", "setupDuplication", "rxTxWorkers", "processWorkers",
            "maxUploadMtu", "maxDownloadMtu", "batch", "window", "dataNackGap", "compressionMinSize",
            "initialRtoSeconds", "controlInitialRtoSeconds", "pingSeconds")
        val json = keys.joinToString(",", "{", "}") { "\"$it\":2" }
        assertEquals(json, MasterDnsGoodput.validatePolicy(json, 2))
        assertThrows(IllegalArgumentException::class.java) { MasterDnsGoodput.validatePolicy(json, 4) }
        assertThrows(IllegalArgumentException::class.java) { MasterDnsGoodput.validatePolicy(json.dropLast(1) + ",\"key\":\"secret\"}", 2) }
    }
}
