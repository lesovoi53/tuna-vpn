package com.wireturn.app

import com.wireturn.app.domain.ProxyHttpText
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A local HTTP/SOCKS fixture. Never opens a public endpoint or reads user settings. */
internal object DiagnosticHttpAudit {
    suspend fun run(): String {
        for (mode in listOf("normal", "large", "redirect", "timeout", "cancel")) {
            val executor = Executors.newSingleThreadExecutor()
            val ready = CompletableDeferred<Unit>()
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 3000
                try {
                    executor.submit {
                        listener.accept().use { socket ->
                            socket.soTimeout = 3000
                            val input = DataInputStream(socket.getInputStream())
                            check(input.readUnsignedByte() == 5)
                            input.readFully(ByteArray(input.readUnsignedByte()))
                            val output = socket.getOutputStream()
                            output.write(byteArrayOf(5, 0))
                            input.readFully(ByteArray(3))
                            val type = input.readUnsignedByte()
                            input.readFully(ByteArray(when(type) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS") }))
                            input.readUnsignedShort()
                            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                            val reader = input.bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            ready.complete(Unit)
                            if (mode == "timeout" || mode == "cancel") {
                                output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                                repeat(10) { output.write("1\r\nx\r\n".toByteArray()); output.flush(); Thread.sleep(350) }
                                output.write("0\r\n\r\n".toByteArray())
                            } else {
                                val body = if (mode == "large") "x".repeat(65536) else "DE"
                                val status = if (mode == "redirect") "302 Found" else "200 OK"
                                output.write("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            }
                        }
                    }
                    val started = System.nanoTime()
                    supervisorScope {
                        val request = async { ProxyHttpText.fetch("http://127.0.0.1:80/fixture",
                            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", listener.localPort)),
                            totalTimeoutMs = if (mode == "cancel") 5000 else 1000) }
                        when (mode) {
                            "normal" -> check(request.await() == "DE")
                            "large", "redirect" -> {
                                val error = runCatching { request.await() }.exceptionOrNull()
                                check(error is IllegalArgumentException) { "$mode: ${error?.javaClass?.simpleName}" }
                            }
                            "timeout" -> {
                                check(runCatching { request.await() }.exceptionOrNull() is TimeoutCancellationException)
                                check((System.nanoTime() - started) / 1_000_000 < 2000) { "Total deadline exceeded" }
                            }
                            "cancel" -> {
                                withTimeout(3000) { ready.await() }; delay(100)
                                val cancelled = System.nanoTime()
                                request.cancelAndJoin()
                                check((System.nanoTime() - cancelled) / 1_000_000 < 1500) { "Cancellation kept reading" }
                            }
                        }
                    }
                } finally { executor.shutdownNow(); executor.awaitTermination(4, TimeUnit.SECONDS) }
            }
        }
        return "5 local Android HTTP diagnostic scenarios passed"
    }
}
