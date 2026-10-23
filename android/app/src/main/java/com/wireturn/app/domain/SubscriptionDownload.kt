package com.wireturn.app.domain

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

internal data class SubscriptionResponse(val code: Int, val bytes: ByteArray, val headers: Map<String, List<String>>) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/** Download completes before any parsing, routing asset preparation or persistent mutation. */
internal object SubscriptionDownload {
    suspend fun fetch(url: String, proxy: Proxy, requireTunnel: Boolean, userAgent: String,
                      totalTimeoutMs: Long = 120_000,
                      factory: (URL, Proxy) -> HttpURLConnection = { address, route -> address.openConnection(route) as HttpURLConnection }): SubscriptionResponse {
        require(totalTimeoutMs > 0)
        try {
            return withTimeout(totalTimeoutMs) {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val owner = currentCoroutineContext()
                        val active = AtomicReference<HttpURLConnection?>(null)
                        val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { active.getAndSet(null)?.disconnect() }
                        }
                        try {
                            // Every redirect and permitted fallback must replace the active
                            // connection before blocking on headers or reading its body.
                            val connection = SubscriptionHttpTransport.openChain(url, proxy, requireTunnel, userAgent) { address, route ->
                                owner.ensureActive()
                                factory(address, route).also {
                                    active.set(it)
                                    // Covers cancellation between creating and registering the connection.
                                    owner.ensureActive()
                                }
                            }
                            val code = connection.responseCode
                            val output = ByteArrayOutputStream()
                            if (code == HttpURLConnection.HTTP_OK) connection.inputStream.use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    owner.ensureActive()
                                    val count = input.read(buffer)
                                    owner.ensureActive()
                                    if (count < 0) break
                                    require(output.size() + count <= 4 * 1024 * 1024) { "Subscription too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            val headers = connection.headerFields.entries.filter { it.key != null }
                                .associate { it.key!! to it.value.toList() }
                            SubscriptionResponse(code, output.toByteArray(), headers)
                        } finally {
                            active.getAndSet(null)?.disconnect()
                            closer.cancelAndJoin()
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("Subscription download exceeded its time limit")
        }
    }
}
