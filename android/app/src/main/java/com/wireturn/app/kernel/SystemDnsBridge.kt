package com.wireturn.app.kernel

import android.content.Context
import android.net.DnsResolver
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Per-child authenticated abstract Unix socket. No DNS names or answers enter logs. */
class SystemDnsBridge(context: Context) : Closeable {
    val socketName = "tuna-dns-${UUID.randomUUID()}"
    private val closed = AtomicBoolean()
    private val clients = ConcurrentHashMap<LocalSocket, CancellationSignal>()
    private val pending = ConcurrentHashMap<CancellationSignal, Future<*>>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8), daemon("dns-worker"))
    private val timer = ScheduledThreadPoolExecutor(1, daemon("dns-deadline")).apply { removeOnCancelPolicy = true }
    private val lease = PhysicalNetwork.acquire(context) { cancelClients() }
    private val server = try { LocalServerSocket(socketName) } catch (error: Exception) {
        lease.close(); workers.shutdownNow(); timer.shutdownNow(); throw error
    }
    private val acceptor = Thread({ accept() }, "dns-accept").apply { isDaemon = true; start() }

    fun applyTo(environment: MutableMap<String, String>) { environment["TUNA_SYSTEM_DNS_SOCKET"] = socketName }

    private fun accept() {
        while (!closed.get()) {
            val client = try { server.accept() } catch (_: Exception) { break }
            val signal = CancellationSignal()
            clients[client] = signal
            if (closed.get()) { finish(client, signal); break }
            // Queue time counts against the connection limit; a stalled peer cannot hold a slot.
            val expiry = SystemClock.elapsedRealtime() + 5000
            val timeout = try { timer.schedule({ finish(client, signal) }, 5000, TimeUnit.MILLISECONDS) }
                catch (_: RejectedExecutionException) { finish(client, signal); break }
            try {
                workers.execute {
                    try { serve(client, signal, expiry) } catch (_: Exception) { /* EOF means query failure. */ }
                    finally { timeout.cancel(false); finish(client, signal) }
                }
            } catch (_: RejectedExecutionException) { timeout.cancel(false); finish(client, signal) }
        }
    }

    private fun serve(client: LocalSocket, signal: CancellationSignal, expiry: Long) {
        require(client.peerCredentials.uid == Process.myUid())
        client.soTimeout = 5000
        val input = DataInputStream(client.inputStream)
        val deadline = minOf(expiry, SystemClock.elapsedRealtime() + input.readUnsignedShort().coerceIn(1, 5000))
        val selected = PhysicalNetwork.snapshot()
        val network = requireNotNull(selected.network) { "Physical network unavailable" }
        // net.Resolver may reuse the connection for its next framed query.
        while (!closed.get() && !signal.isCanceled) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            client.soTimeout = remaining.toInt()
            val size = input.readUnsignedShort()
            require(size in 17..4096)
            val query = ByteArray(size).also(input::readFully)
            val question = SystemDnsWire.question(query)
            require(PhysicalNetwork.snapshot() == selected)
            val future = CompletableFuture<ByteArray>()
            val cancel = timer.schedule({ finish(client, signal) },
                maxOf(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
            try {
                val answer = if (Build.VERSION.SDK_INT >= 29) {
                    DnsResolver.getInstance().rawQuery(network, query, DnsResolver.FLAG_EMPTY, Executor { it.run() }, signal,
                        object : DnsResolver.Callback<ByteArray> {
                            override fun onAnswer(answer: ByteArray, rcode: Int) { future.complete(answer) }
                            override fun onError(error: DnsResolver.DnsException) { future.completeExceptionally(error) }
                        })
                    awaitResult(future, signal, deadline)
                } else {
                    // libc calls cannot be interrupted reliably: process-wide two-slot executor
                    // bounds abandoned work even across repeated service restarts.
                    val legacy = legacyWorkers.submit<ByteArray> {
                        SystemDnsWire.answer(query, question, network.getAllByName(question.name))
                    }
                    try { awaitResult(legacy, signal, deadline) }
                    finally { legacy.cancel(true) }
                }
                require(!signal.isCanceled && !closed.get() && PhysicalNetwork.snapshot() == selected)
                require(SystemClock.elapsedRealtime() < deadline && answer.size in 12..65535)
                DataOutputStream(client.outputStream).apply { writeShort(answer.size); write(answer); flush() }
            } finally { cancel.cancel(false) }
        }
    }

    private fun finish(client: LocalSocket, signal: CancellationSignal) {
        if (!clients.remove(client, signal)) return
        signal.cancel()
        pending.remove(signal)?.cancel(true)
        // close alone does not wake a native read holding the descriptor on Android.
        runCatching { client.shutdownInput() }
        runCatching { client.shutdownOutput() }
        runCatching { client.close() }
    }
    private fun <T> awaitResult(future: Future<T>, signal: CancellationSignal, deadline: Long): T {
        pending[signal] = future
        try {
            if (signal.isCanceled || closed.get()) future.cancel(true)
            return future.get(maxOf(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
        } finally { pending.remove(signal, future) }
    }
    private fun cancelClients() { clients.forEach { (client, signal) -> finish(client, signal) } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Wake the blocking accept before closing: its kernel reference can otherwise
        // keep the abstract socket alive until another connection arrives.
        runCatching { Os.shutdown(server.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { server.close() }
        cancelClients(); workers.shutdownNow(); timer.shutdownNow(); lease.close()
    }
    companion object {
        private fun daemon(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
        private val legacyWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, SynchronousQueue(), daemon("dns-legacy"))
    }
}
