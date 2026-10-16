package com.wireturn.app.domain

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.*

/** Small text responses used only for diagnostics through the active tunnel. */
internal object ProxyHttpText {
    suspend fun fetch(url: String, proxy: Proxy, timeoutMs: Int = 3000, maxChars: Int = 8192, totalTimeoutMs: Long = timeoutMs * 2L): String {
        require(proxy.type() == Proxy.Type.SOCKS) { "Active tunnel required" }
        require(timeoutMs > 0 && maxChars in 1..65536 && totalTimeoutMs > 0)
        return withTimeout(totalTimeoutMs) {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val connection = URL(url).openConnection(proxy) as HttpURLConnection
                    val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { connection.disconnect() }
                    }
                    try {
                        connection.connectTimeout = timeoutMs
                        connection.readTimeout = timeoutMs
                        connection.instanceFollowRedirects = false
                        require(connection.responseCode == 200) { "Diagnostic endpoint unavailable" }
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val chars = CharArray(maxChars + 1)
                            var size = 0
                            while (size < chars.size) {
                                val count = reader.read(chars, size, chars.size - size)
                                if (count < 0) break
                                size += count
                            }
                            require(size <= maxChars) { "Diagnostic response too large" }
                            String(chars, 0, size)
                        }
                    } finally { closer.cancelAndJoin() }
                }
            }
        }
    }
}
