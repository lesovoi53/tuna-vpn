package com.wireturn.app.domain

import kotlinx.coroutines.*
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Transparent loopback relay. The factory creates an unconnected, Network-bound socket.
 * Does not terminate TLS, change a process-wide network, or listen on the LAN.
 */
class CfTcpRelay(private val createSocket: () -> Socket = { Socket() }, private val connect: (Socket) -> Unit) : Closeable {
    private val listener = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(64)
    @Volatile private var closed = false
    val port: Int get() = listener.localPort
    init {
        scope.launch {
            try {
                while (isActive) {
                    val incoming = listener.accept()
                    if (closed || !slots.tryAcquire()) { incoming.close(); continue }
                    sockets += incoming
                    if (closed) { incoming.close(); sockets -= incoming; slots.release(); break }
                    launch {
                        var outgoing: Socket? = null
                        try {
                            // Own the socket before connect() can block. Closing the
                            // relay now interrupts pending dials as well as active I/O.
                            outgoing = createSocket().also { sockets += it }
                            ensureActive()
                            connect(outgoing)
                            ensureActive()
                            val remote = outgoing
                            coroutineScope {
                                val up = launch { incoming.getInputStream().copyTo(remote.getOutputStream()); runCatching { remote.shutdownOutput() } }
                                try { remote.getInputStream().copyTo(incoming.getOutputStream()) }
                                finally { incoming.close(); remote.close(); up.cancelAndJoin() }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Candidate failure is reported by the authenticated HTTPS probe. */ }
                        finally {
                            runCatching { incoming.close() }; sockets -= incoming
                            outgoing?.let { runCatching { it.close() }; sockets -= it }
                            slots.release()
                        }
                    }
                }
            } catch (_: java.io.IOException) { /* Closing the listener cancels accept. */ }
        }
    }
    override fun close() {
        closed = true
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        scope.cancel()
    }
    suspend fun awaitClosed() { close(); scope.coroutineContext[Job]?.join() }
}
