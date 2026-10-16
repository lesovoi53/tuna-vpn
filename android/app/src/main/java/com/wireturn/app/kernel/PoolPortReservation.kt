package com.wireturn.app.kernel

import java.net.InetAddress
import java.net.ServerSocket

/** Holds all ports until the whole pool is ready to start. Safe to close more than once. */
class PoolPortReservation(channels: Int) : AutoCloseable {
    private var udp: java.net.DatagramSocket? = null
    private val sockets = mutableListOf<ServerSocket>()
    val listenPort: Int
    val channelPorts: List<Int>
    init {
        require(channels in 2..8)
        try {
            val address = InetAddress.getByName("127.0.0.1")
            for (attempt in 1..32) {
                // TCP's automatic allocation can repeatedly land inside an OS UDP exclusion
                // range. After a collision spread retries across the dynamic port range.
                val candidate = if (attempt == 1) 0 else java.util.concurrent.ThreadLocalRandom.current().nextInt(49152, 65536)
                val tcp = try { ServerSocket(candidate, 1, address) }
                    catch (_: java.net.BindException) { continue }
                try { udp = java.net.DatagramSocket(tcp.localPort, address); sockets += tcp; break }
                catch (_: java.net.BindException) { tcp.close() }
            }
            check(udp != null) { "Нет свободного TCP/UDP порта для теста" }
            repeat(channels) { sockets += ServerSocket(0, 1, address) }
            listenPort = sockets.first().localPort
            channelPorts = sockets.drop(1).map { it.localPort }
        } catch (e: Exception) { close(); throw e }
    }
    override fun close() { udp?.close(); sockets.forEach { runCatching { it.close() } } }
}
