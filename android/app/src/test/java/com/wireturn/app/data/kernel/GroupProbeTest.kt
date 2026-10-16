package com.wireturn.app.data.kernel

import com.wireturn.app.data.*
import com.wireturn.app.domain.GroupProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GroupProbeTest {
    @Test fun legacySpeedProbeIsRejectedBeforeOpeningASocket() {
        val legacy = group.copy(type = GroupTestType.SPEEDTEST)
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { GroupProbe.measure(legacy, "a", ClientConfig(socksAddr = "127.0.0.1:1")) }
        }
        assertEquals("Поддерживается только URL-test", error.message)
    }

    @Test fun aSlowSocksGreetingCannotExtendTheOverallProbeDeadline() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 4000
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = DataInputStream(socket.getInputStream())
                    input.readFully(ByteArray(3))
                    try {
                        Thread.sleep(1300)
                        socket.getOutputStream().write(5)
                        Thread.sleep(1300)
                        socket.getOutputStream().write(0)
                        input.readFully(ByteArray(3))
                        socket.getOutputStream().write(byteArrayOf(5, 5))
                    } catch (_: java.io.IOException) { /* The deadline closes the client socket. */ }
                }
            }
            try {
                val started = System.nanoTime()
                val failure = assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
                    runBlocking { GroupProbe.measure(group, "a", ClientConfig(
                        socksAddr = "127.0.0.1:${server.localPort}", isSocksAuthEnabled = false)) }
                }
                assertTrue("The 2-second deadline includes SOCKS negotiation", (System.nanoTime() - started) / 1_000_000 < 2500)
                assertTrue(GroupProbe.failureMessage(failure).contains("Проба 1/1: SOCKS — авторизация."))
            } finally {
                peer.cancel(true); executor.shutdownNow(); executor.awaitTermination(4, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun methodsSurviveStorageAndOldGroupsKeepGet() {
        val gson = com.google.gson.Gson()
        for (method in GroupProbeMethod.entries) {
            val restored = gson.fromJson(gson.toJson(group.copy(probeMethod = method)), ConnectionGroup::class.java)
            assertEquals(method, restored.effectiveProbeMethod)
        }
        val legacy = gson.toJsonTree(group).asJsonObject.apply { remove("probeMethod") }
        assertEquals(GroupProbeMethod.GET, gson.fromJson(legacy, ConnectionGroup::class.java).effectiveProbeMethod)
    }

    @Test fun savedTcpMethodFallsBackToGetWithoutLosingGroup() {
        val gson = com.google.gson.Gson()
        val stored = gson.toJsonTree(group).asJsonObject.apply { addProperty("probeMethod", "TCP") }
        val restored = gson.fromJson(stored, ConnectionGroup::class.java)
        assertEquals(GroupProbeMethod.GET, restored.effectiveProbeMethod)
        assertEquals(group.memberIds, restored.memberIds)
        assertEquals(group.testUrl, restored.testUrl)
    }

    private val group = ConnectionGroup("g", "Test", ConnectionCategory.VPN, GroupTestType.URL_TEST,
        listOf("a", "b"), timeoutSeconds = 2, samples = 1, testUrl = "https://destination.invalid/test")

    @Test fun socksAuthIsRequiredAndDestinationIsResolvedRemotely() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val observed = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()
                        val greeting = ByteArray(3).also { input.readFully(it) }
                        assertArrayEquals(byteArrayOf(5, 1, 2), greeting)
                        out.write(byteArrayOf(5, 2)); out.flush()
                        assertEquals(1, input.readUnsignedByte())
                        val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                        val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                        assertEquals("test-user", user); assertEquals("test-password", pass)
                        out.write(byteArrayOf(1, 0)); out.flush()
                        assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte()); assertEquals(3, input.readUnsignedByte())
                        val host = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                        assertEquals(443, input.readUnsignedShort())
                        // Refusal must be terminal; a direct DNS lookup or retry would fail this test.
                        out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                        host
                    }
                }
                val config = ClientConfig(socksAddr = "127.0.0.1:${server.localPort}", socksUser = "test-user", socksPass = "test-password")
                val failure = assertThrows(IllegalArgumentException::class.java) { runBlocking { GroupProbe.measure(group, "a", config) } }
                assertEquals("Сервер не открыл тестовый адрес", failure.message)
                val diagnostic = GroupProbe.failureMessage(failure)
                assertTrue(diagnostic, diagnostic.contains("Проба 1/1: SOCKS — подключение к узлу теста."))
                assertFalse(diagnostic.contains("destination.invalid"))
                assertFalse(diagnostic.contains("test-password"))
                assertFalse(diagnostic.contains("test-user"))
                assertEquals("destination.invalid", observed.get(5, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun remoteProxyAddressRejectedBeforeDial() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { GroupProbe.measure(group, "a", ClientConfig(socksAddr = "192.0.2.1:1234")) }
        }
    }

    @Test fun selectionFailureAfterSocksConnectIdentifiesTlsAndFiveSamplePlan() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        input.readFully(ByteArray(3))
                        socket.getOutputStream().write(byteArrayOf(5, 0))
                        input.readFully(ByteArray(4)) // SOCKS domain destination
                        input.readFully(ByteArray(input.readUnsignedByte() + 2))
                        socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                        socket.getOutputStream().flush()
                        input.read() // Client starts TLS, peer closes before ServerHello.
                    }
                }
                val failure = assertThrows(javax.net.ssl.SSLException::class.java) {
                    runBlocking { GroupProbe.measure(group.copy(samples = 5, probeMethod = GroupProbeMethod.HEAD), "a", ClientConfig(
                        socksAddr = "127.0.0.1:${server.localPort}", isSocksAuthEnabled = false)) }
                }
                val diagnostic = GroupProbe.failureMessage(failure)
                assertTrue(diagnostic, diagnostic.contains("Проба 1/5: TLS — защищённое соединение."))
                peer.get(4, TimeUnit.SECONDS)
                server.soTimeout = 150
                assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().close() }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun cancellingTestClosesBlockedSocket() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val closed = executor.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                        socket.getInputStream().read()
                    }
                }
                assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
                    runBlocking {
                        kotlinx.coroutines.withTimeout(500) {
                            GroupProbe.measure(group.copy(timeoutSeconds = 120), "a",
                                ClientConfig(socksAddr = "127.0.0.1:${server.localPort}", socksUser = "u", socksPass = "p"))
                        }
                    }
                }
                assertEquals(-1, closed.get(4, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
}
