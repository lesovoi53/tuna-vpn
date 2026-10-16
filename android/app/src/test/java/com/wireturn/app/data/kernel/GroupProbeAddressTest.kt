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

class GroupProbeAddressTest {
    private fun group(url: String) = ConnectionGroup("g", "Test", ConnectionCategory.VPN, GroupTestType.URL_TEST,
        listOf("a", "b"), timeoutSeconds = 1, samples = 1, testUrl = url)

    @Test fun numericTargetsUseSocksAddressTypesWithoutDomainResolution() {
        for ((host, expected) in listOf("192.0.2.1" to 1, "[2001:db8::1]" to 4, "destination.invalid" to 3)) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 2000
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val captured = pool.submit<Pair<Int, ByteArray>> {
                        server.accept().use { socket ->
                            socket.soTimeout = 2000
                            val input = DataInputStream(socket.getInputStream())
                            input.readFully(ByteArray(3))
                            socket.getOutputStream().write(byteArrayOf(5, 0))
                            assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                            assertEquals(0, input.readUnsignedByte())
                            val type = input.readUnsignedByte()
                            val bytes = ByteArray(when(type) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("address type") })
                            input.readFully(bytes)
                            assertEquals(8443, input.readUnsignedShort())
                            socket.getOutputStream().write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                            type to bytes
                        }
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        runBlocking { GroupProbe.measure(group("https://$host:8443/test"), "a",
                            ClientConfig(socksAddr = "127.0.0.1:${server.localPort}", isSocksAuthEnabled = false)) }
                    }
                    val (type, bytes) = captured.get(3, TimeUnit.SECONDS)
                    assertEquals("SOCKS address type for $host", expected, type)
                    if (expected == 3) assertEquals(host, bytes.decodeToString())
                    else assertArrayEquals(InetAddress.getByName(host.removeSurrounding("[", "]")).address, bytes)
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun invalidDestinationPortIsRejectedBeforeOpeningTheProxy() {
        for (port in listOf(0, 65536, 70000)) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 100
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { GroupProbe.measure(group("https://destination.invalid:$port/test"), "a",
                        ClientConfig(socksAddr = "127.0.0.1:${server.localPort}", isSocksAuthEnabled = false)) }
                }
                assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().close() }
            }
        }
    }
}
