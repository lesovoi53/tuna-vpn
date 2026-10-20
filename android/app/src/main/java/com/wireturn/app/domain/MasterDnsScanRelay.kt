package com.wireturn.app.domain

import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Native scan traffic goes through these loopback endpoints; only the remote sockets are bound
 * to the chosen Android Network. A single selector owns bounded transaction maps and all I/O.
 */
internal class MasterDnsScanRelay(
    endpoints: List<MasterDnsEndpoint>,
    connectSocket: (DatagramChannel, InetSocketAddress) -> Unit = { channel, address -> channel.connect(address); Unit },
    /** UDP payload bytes sent to/received from remote endpoints, including protocol overhead. */
    private val byteBudget: Long = Long.MAX_VALUE,
    bindSocket: (DatagramSocket) -> Unit
) : Closeable {
    private data class Pending(val original: ByteArray, val peer: SocketAddress, val expires: Long)
    private data class Relay(
        val endpoint: MasterDnsEndpoint,
        val local: DatagramChannel,
        val remote: DatagramChannel?,
        val pending: MutableMap<Int, Pending> = mutableMapOf()
    )
    private data class Direction(val relay: Relay, val fromRemote: Boolean)
    private val selector = Selector.open()
    private val channels = mutableListOf<DatagramChannel>()
    private val relays = mutableListOf<Relay>()
    private val failure = AtomicReference<Throwable?>()
    // Leave room for the largest already-arrived datagram. UDP/IP/link headers and
    // unsolicited packets after close cannot be accounted as application payload.
    private val stopThreshold = if (byteBudget == Long.MAX_VALUE) byteBudget else (byteBudget - 65535).coerceAtLeast(0)
    @Volatile private var closed = false
    @Volatile var wireBytes: Long = 0
        private set
    private val thread: Thread
    val resolverMapping: Map<String, String>

    init {
        require(endpoints.size in 1..128)
        require(byteBudget > 0)
        try {
            endpoints.forEach { endpoint ->
                val local = DatagramChannel.open().also { channels += it }
                local.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0))
                local.configureBlocking(false)
                val remote = DatagramChannel.open().also { channels += it }
                bindSocket(remote.socket()) // Fail closed: never retry on the default/VPN network.
                val connected = try {
                    connectSocket(remote, InetSocketAddress(endpoint.address, endpoint.port))
                    remote
                } catch (_: IOException) {
                    // An endpoint can lack a route (e.g. IPv6 on IPv4-only Wi-Fi).
                    // Retain its loopback endpoint as a silent sink so native marks it failed,
                    // while other resolvers continue. Binding errors above remain fatal.
                    remote.close()
                    null
                }
                connected?.configureBlocking(false)
                val relay = Relay(endpoint, local, connected)
                relays += relay
                local.register(selector, SelectionKey.OP_READ, Direction(relay, false))
                connected?.register(selector, SelectionKey.OP_READ, Direction(relay, true))
            }
            resolverMapping = relays.associate {
                "127.0.0.1:${(it.local.localAddress as InetSocketAddress).port}" to it.endpoint.canonical
            }
            thread = Thread({ pump() }, "masterdns-network-relay").apply { isDaemon = true; start() }
        } catch (error: Throwable) {
            channels.forEach { runCatching { it.close() } }
            selector.close()
            throw error
        }
    }

    fun checkHealthy() { failure.get()?.let { throw java.io.IOException("Сбой сети сканирования", it) } }

    private fun pump() {
        val buffer = ByteBuffer.allocate(65535)
        val random = SecureRandom()
        try {
            while (!closed) {
                selector.select(200)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next().also { keys.remove() }
                    if (!key.isValid) continue
                    val direction = key.attachment() as Direction
                    val relay = direction.relay
                    val channel = if (direction.fromRemote) relay.remote ?: continue else relay.local
                    repeat(32) {
                        buffer.clear()
                        val peer = try { channel.receive(buffer) } catch (_: java.net.PortUnreachableException) { null }
                            ?: return@repeat
                        val bytes = ByteArray(buffer.position()).also { buffer.flip(); buffer.get(it) }
                        // Count received datagrams even when malformed.
                        if (direction.fromRemote) {
                            wireBytes += bytes.size
                            if (wireBytes > stopThreshold) throw IOException("Лимит UDP-трафика диагностики исчерпан")
                        }
                        if (bytes.size < 12) return@repeat
                        val now = System.nanoTime()
                        relay.pending.entries.removeAll { it.value.expires < now }
                        if (direction.fromRemote) {
                            val pending = relay.pending[MasterDnsScanWire.id(bytes)] ?: return@repeat
                            val restored = bytes.copyOf()
                            MasterDnsScanWire.setId(restored, MasterDnsScanWire.id(pending.original))
                            if (!MasterDnsScanWire.responseMatches(pending.original, restored)) return@repeat
                            relay.pending.remove(MasterDnsScanWire.id(bytes))
                            relay.local.send(ByteBuffer.wrap(restored), pending.peer)
                        } else {
                            val remote = relay.remote ?: return@repeat
                            if (!(peer as InetSocketAddress).address.isLoopbackAddress || relay.pending.size >= 128) return@repeat
                            if (bytes[2].toInt() and 0x80 != 0 || runCatching { MasterDnsScanWire.question(bytes) }.isFailure) return@repeat
                            var replacement = random.nextInt(65536)
                            while (replacement in relay.pending) replacement = (replacement + 1) and 65535
                            val forwarded = bytes.copyOf()
                            MasterDnsScanWire.setId(forwarded, replacement)
                            if (forwarded.size > stopThreshold - wireBytes) throw IOException("Лимит UDP-трафика диагностики исчерпан")
                            wireBytes += forwarded.size
                            relay.pending[replacement] = Pending(bytes, peer, now + 10_000_000_000L)
                            try { remote.write(ByteBuffer.wrap(forwarded)) }
                            catch (_: java.net.PortUnreachableException) { relay.pending.remove(replacement) }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed) failure.compareAndSet(null, error)
        } finally {
            if (failure.get() != null) {
                closed = true
                channels.forEach { runCatching { it.close() } }
                runCatching { selector.close() }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { selector.wakeup() }
        channels.forEach { runCatching { it.close() } }
        runCatching { selector.close() }
        if (Thread.currentThread() !== thread) thread.join(1500)
    }
}
