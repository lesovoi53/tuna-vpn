package com.wireturn.app

import com.wireturn.app.domain.SubscriptionDownload
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executes the production downloader only against a loopback fixture, without user state. */
internal object SubscriptionDownloadAudit {
    suspend fun run(): String {
        for (mode in listOf("normal", "large", "server_error", "timeout", "cancel_headers")) {
            val executor = Executors.newSingleThreadExecutor()
            val ready = CompletableDeferred<Unit>()
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 4000
                try {
                    executor.submit {
                        listener.accept().use { socket ->
                            socket.soTimeout = 4000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) {}
                            ready.complete(Unit)
                            if (mode == "cancel_headers") Thread.sleep(3000)
                            val output = socket.getOutputStream()
                            if (mode == "timeout") {
                                output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                                repeat(10) { output.write("1\r\nx\r\n".toByteArray()); output.flush(); Thread.sleep(300) }
                                output.write("0\r\n\r\n".toByteArray())
                            } else {
                                val body = if (mode == "large") "x".repeat(4 * 1024 * 1024 + 1) else "{}"
                                val status = if (mode == "server_error") "503 Unavailable" else "200 OK"
                                output.write("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nProfile-Title: Fixture\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(body.toByteArray())
                            }
                        }
                    }
                    supervisorScope {
                        val started = System.nanoTime()
                        val request = async { SubscriptionDownload.fetch("http://127.0.0.1:${listener.localPort}/",
                            Proxy.NO_PROXY, false, "TUNA local fixture", totalTimeoutMs = if (mode == "timeout") 1000 else 5000) }
                        when (mode) {
                            "normal" -> request.await().let {
                                check(it.code == 200 && it.bytes.toString(Charsets.UTF_8) == "{}" && it.header("profile-title") == "Fixture")
                            }
                            "server_error" -> request.await().let { check(it.code == 503 && it.bytes.isEmpty()) }
                            "large" -> check(runCatching { request.await() }.exceptionOrNull() is IllegalArgumentException)
                            "timeout" -> {
                                check(runCatching { request.await() }.exceptionOrNull() is SocketTimeoutException)
                                check((System.nanoTime() - started) / 1_000_000 < 2200) { "Subscription total deadline exceeded" }
                            }
                            "cancel_headers" -> {
                                withTimeout(3000) { ready.await() }
                                val cancelled = System.nanoTime(); request.cancelAndJoin()
                                check((System.nanoTime() - cancelled) / 1_000_000 < 1500) { "Cancellation waited for headers" }
                            }
                        }
                    }
                } finally { executor.shutdownNow(); executor.awaitTermination(4, TimeUnit.SECONDS) }
            }
        }
        return "5 local Android subscription download scenarios passed"
    }
}
