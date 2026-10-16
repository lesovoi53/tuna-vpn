package com.wireturn.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CfTcpRelayTest {
    @Test fun cancellingRelayClosesSocketWhileItsConnectIsBlocked(): Unit = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val pending = object : Socket() {
            override fun close() { super.close(); released.countDown() }
        }
        val relay = CfTcpRelay({ pending }) {
            started.countDown()
            if (!released.await(3, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("dial")
            throw java.net.SocketException("closed during connect")
        }
        try {
            Socket("127.0.0.1", relay.port).use {
                assertTrue(started.await(3, TimeUnit.SECONDS))
                relay.close()
                assertTrue("Cancel left the pending connect alive until its timeout", pending.isClosed)
                withTimeout(1000) { relay.awaitClosed() }
            }
        } finally { pending.close(); withTimeout(3000) { relay.awaitClosed() } }
    }
    @Test fun relayForwardsBytesAndClosesIdleConnectionsOnCancellation(): Unit = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val factoryCalls = AtomicInteger()
        val echo = launch(Dispatchers.IO) {
            server.accept().use { remote ->
                remote.soTimeout = 3000
                val bytes = ByteArray(4)
                java.io.DataInputStream(remote.getInputStream()).readFully(bytes)
                remote.getOutputStream().write(bytes)
                assertEquals(-1, remote.getInputStream().read())
            }
        }
        val relay = CfTcpRelay { socket -> factoryCalls.incrementAndGet(); socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)) }
        val port = relay.port
        try {
            Socket("127.0.0.1", port).use { local ->
                local.soTimeout = 3000
                local.getOutputStream().write(byteArrayOf(0, 1, 2, -1))
                val received = ByteArray(4); java.io.DataInputStream(local.getInputStream()).readFully(received)
                assertArrayEquals(byteArrayOf(0, 1, 2, -1), received)
                withTimeout(3000) { relay.awaitClosed() }
                assertEquals(-1, local.getInputStream().read())
            }
            echo.join()
            assertEquals(1, factoryCalls.get())
            assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", port).close() }
        } finally { relay.close(); server.close(); echo.cancelAndJoin() }
    }
}
