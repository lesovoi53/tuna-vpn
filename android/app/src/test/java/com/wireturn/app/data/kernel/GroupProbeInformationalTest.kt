package com.wireturn.app.data.kernel

import com.wireturn.app.data.*
import com.wireturn.app.domain.GroupProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Real loopback SOCKS/TLS; only this test JVM trusts the fixture certificate. */
class GroupProbeInformationalTest {
    private fun measure(response: String): Double {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/group-probe-fixture.p12")!!.use { store.load(it, "fixture-only".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        val original = SSLContext.getDefault()
        val executor = Executors.newSingleThreadExecutor()
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            try {
                SSLContext.setDefault(ssl)
                val remote = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        input.readFully(ByteArray(3)); socket.getOutputStream().write(byteArrayOf(5, 0))
                        input.readFully(ByteArray(3)); val type = input.readUnsignedByte()
                        input.readFully(ByteArray(when (type) { 1 -> 4; 4 -> 16; else -> input.readUnsignedByte() }))
                        input.readUnsignedShort()
                        socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                        (ssl.socketFactory.createSocket(socket, "localhost", 443, true) as SSLSocket).use { tls ->
                            tls.useClientMode = false; tls.startHandshake()
                            val reader = tls.inputStream.bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            tls.outputStream.write(response.toByteArray()); tls.outputStream.flush()
                        }
                    }
                }
                val group = ConnectionGroup("g", "fixture", ConnectionCategory.VPN,
                    GroupTestType.URL_TEST, listOf("a", "b"),
                    timeoutSeconds = 3, testUrl = "https://localhost/", expectedStatus = 200, samples = 1)
                val result = runBlocking { GroupProbe.measure(group, "a",
                    ClientConfig(socksAddr = "127.0.0.1:${server.localPort}", isSocksAuthEnabled = false)) }
                remote.get(4, TimeUnit.SECONDS)
                result.bytesPerSecond
            } finally {
                SSLContext.setDefault(original); executor.shutdownNow(); executor.awaitTermination(4, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun earlyHintsBeforeSuccessDoNotMarkAWorkingProfileUnavailable() {
        measure("HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
    }
    @Test fun protocolSwitchDoesNotCountAsSuccessfulHttpsProbe() {
        assertThrows(IllegalArgumentException::class.java) {
            measure("HTTP/1.1 101 Switching Protocols\r\nUpgrade: other\r\n\r\nHTTP/1.1 200 OK\r\n\r\n")
        }
    }
    @Test fun informationalResponseAloneIsNotAConfirmedConnection() {
        assertThrows(IllegalArgumentException::class.java) { measure("HTTP/1.1 103 Early Hints\r\n\r\n") }
    }
    @Test fun interimFloodHasABoundedHeaderBudget() {
        assertThrows(IllegalArgumentException::class.java) {
            measure("HTTP/1.1 103 Early Hints\r\n\r\n".repeat(9) + "HTTP/1.1 200 OK\r\n\r\n")
        }
    }
    @Test fun nonHttpTextWithSuccessNumberCannotPass() {
        val failure = assertThrows(IllegalArgumentException::class.java) { measure("INVALID 200 secret-peer-text\r\n\r\n") }
        val diagnostic = GroupProbe.failureMessage(failure)
        assertTrue(diagnostic, diagnostic.contains("Проба 1/1: HTTP — ответ узла теста."))
        assertFalse(diagnostic.contains("secret-peer-text"))
    }
}
