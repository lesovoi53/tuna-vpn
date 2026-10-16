package com.wireturn.app.domain

import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.ConnectionGroup
import com.wireturn.app.data.GroupTestType
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Dials the kernel's own SOCKS endpoint, bypassing the optional routing proxy.
 * Names are sent as SOCKS domains, numeric targets use IPv4/IPv6 address fields.
 * Neither DNS nor HTTP can fall back to direct.
 * Credentials are connection-local; JVM-global Authenticators are intentionally not used.
 */
data class ProbeProgress(val sample: Int, val samples: Int, val bytes: Long = 0, val elapsedMs: Long = 0, val stage: String? = null)

object GroupProbe {
    private class HttpProbeException(val status: Int?) : java.io.IOException("HTTP")

    private enum class Stage(val label: String) {
        VALIDATION("параметры теста"), LOCAL_CONNECT("подключение к локальному прокси"),
        SOCKS_AUTH("SOCKS — авторизация"), SOCKS_CONNECT("SOCKS — подключение к узлу теста"),
        TLS("TLS — защищённое соединение"), HTTP("HTTP — ответ узла теста")
    }

    // Only fixed labels and sample counters enter the UI; never copy exception/peer text.
    private class SampleFailure(val sample: Int, val samples: Int, val stage: Stage) : Exception()

    /** Never expose server-controlled text, URLs, proxy credentials or TLS diagnostics in the UI. */
    fun failureMessage(error: Throwable): String {
        // Coroutine stacktrace recovery can copy the throwable and keep the original as cause.
        val sample = generateSequence(error) { it.cause }.take(8)
            .flatMap { it.suppressed.asSequence() }.filterIsInstance<SampleFailure>().firstOrNull()
        val prefix = sample?.let { "Проба ${it.sample}/${it.samples}: ${it.stage.label}. " }.orEmpty()
        return prefix + failureReason(error)
    }

    private fun failureReason(error: Throwable): String = when (error) {
        is TimeoutCancellationException, is java.net.SocketTimeoutException -> "Истекло время теста. Увеличьте таймаут."
        is javax.net.ssl.SSLException -> "Ошибка TLS: не удалось подтвердить защищённое соединение."
        is HttpProbeException -> "Ошибка HTTP${error.status?.let { " $it" }.orEmpty()}: сервер вернул некорректный или неожиданный ответ."
        is java.net.ConnectException -> "Не удалось подключиться к локальному SOCKS-прокси."
        is java.io.IOException -> "Тестовое соединение прервано."
        else -> "Не удалось выполнить тест. Проверьте параметры и доступность сервера."
    }

    suspend fun measure(group: ConnectionGroup, id: String, config: ClientConfig, onProgress: (ProbeProgress) -> Unit = {}): GroupMeasurement = withContext(Dispatchers.IO) {
        require(group.type == GroupTestType.URL_TEST) { "Поддерживается только URL-test" }
        val timings = mutableListOf<Long>()
        val count = group.samples
        repeat(count) { sample ->
            currentCoroutineContext().ensureActive()
            onProgress(ProbeProgress(sample + 1, count))
            var stage = Stage.VALIDATION
            // SO_TIMEOUT is per read, so a slow peer can otherwise extend SOCKS/TLS
            // negotiation indefinitely by sending a byte before each read times out.
            val result = try { withTimeout(group.timeoutSeconds * 1000L) {
                coroutineScope {
                    val socket = Socket()
                    val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { socket.close() }
                    }
                    try { request(group, config, socket, onStage = { stage = it }) }
                    finally { closer.cancelAndJoin() }
                }
            } } catch (failure: Exception) {
                // Recovery may unwrap this exception at the next suspend boundary, so
                // annotate the original cause too. Traversal is bounded for unusual chains.
                val detail = SampleFailure(sample + 1, count, stage)
                generateSequence(failure as Throwable) { it.cause }.take(8).forEach { it.addSuppressed(detail) }
                throw failure
            }
            timings += result
        }
        GroupMeasurement(id, timings.sorted()[timings.size / 2], measuredAt = System.currentTimeMillis())
    }

    private fun request(group: ConnectionGroup, config: ClientConfig, rawSocket: Socket,
        onStage: (Stage) -> Unit = {}): Long {
        val url = URI(group.testUrl)
        require(url.scheme == "https" && url.userInfo == null && url.fragment == null)
        val host = url.host?.removeSurrounding("[", "]") ?: error("Нет имени сервера теста")
        val port = if (url.port == -1) 443 else url.port
        require(port in 1..65535) { "Порт тестового адреса: 1–65535" }
        val literal = CfAddress.literal(host)
        val destination = if (literal != null) {
            byteArrayOf(if (literal.address.size == 4) 1 else 4) + literal.address
        } else {
            val domain = java.net.IDN.toASCII(host).toByteArray(Charsets.US_ASCII)
            require(domain.size in 1..255)
            byteArrayOf(3, domain.size.toByte()) + domain
        }
        val timeout = group.timeoutSeconds * 1000
        val begin = System.nanoTime()
        val deadline = begin + timeout * 1_000_000L
        fun remaining(): Int = ((deadline - System.nanoTime()) / 1_000_000L)
            .coerceAtMost(timeout.toLong())
            .also { if (it <= 0) throw java.net.SocketTimeoutException("Истекло время теста") }.toInt()
        rawSocket.use { socket ->
            val address = config.socksAddr
            val localHost = address.substringBeforeLast(':').removeSurrounding("[", "]")
            require(localHost in listOf("127.0.0.1", "localhost", "::1", "0.0.0.0"))
            onStage(Stage.LOCAL_CONNECT)
            socket.connect(InetSocketAddress(if (localHost == "0.0.0.0") "127.0.0.1" else localHost,
                address.substringAfterLast(':').toInt()), remaining())
            socket.soTimeout = remaining()
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val auth = config.isSocksAuthEnabled
            onStage(Stage.SOCKS_AUTH)
            output.write(byteArrayOf(5, 1, if (auth) 2 else 0)); output.flush()
            fun socksCheck(valid: Boolean, message: String) {
                require(valid) { message }
            }
            socksCheck(input.readUnsignedByte() == 5 && input.readUnsignedByte() == if (auth) 2 else 0, "SOCKS отклонил авторизацию")
            if (auth) {
                val user = config.socksUser.toByteArray(Charsets.UTF_8)
                val pass = config.socksPass.toByteArray(Charsets.UTF_8)
                require(user.size in 1..255 && pass.size in 1..255)
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); output.flush()
                socksCheck(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0, "Ошибка SOCKS авторизации")
            }
            onStage(Stage.SOCKS_CONNECT)
            output.write(byteArrayOf(5, 1, 0) + destination + byteArrayOf((port shr 8).toByte(), port.toByte())); output.flush()
            socksCheck(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0, "Сервер не открыл тестовый адрес")
            input.readUnsignedByte()
            val length = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS адрес") }
            input.readFully(ByteArray(length + 2))
            onStage(Stage.TLS)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(socket, host, port, true) as SSLSocket).use { tls ->
                try {
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.soTimeout = remaining()
                    tls.startHandshake()
                    onStage(Stage.HTTP)
                    val path = (url.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (url.rawQuery?.let { "?$it" } ?: "")
                    val authority = (if (':' in host) "[$host]" else host) + if (port == 443) "" else ":$port"
                    tls.outputStream.write(("${group.effectiveProbeMethod.name} $path HTTP/1.1\r\nHost: $authority\r\nUser-Agent: TUNA-VPN/GroupTest\r\nAccept-Encoding: identity\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    tls.outputStream.flush()
                    val body = BufferedInputStream(tls.inputStream)
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (bytes.size() < 8192) {
                            tls.soTimeout = remaining()
                            val b = body.read()
                            require(b >= 0) { "Неполный HTTP ответ" }
                            if (b == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                            bytes.write(b)
                        }
                        error("Слишком длинный HTTP заголовок")
                    }
                    var headerCount = 0
                    var informationalCount = 0
                    while (true) {
                        val status = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*)?").matchEntire(line())
                            ?.groupValues?.get(1)?.toIntOrNull()
                        require(status != null && status != 101) { "Некорректный HTTP ответ" }
                        val interim = status in 100..199
                        if (interim) require(++informationalCount <= 8) { "Слишком много промежуточных HTTP ответов" }
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            require(++headerCount <= 100)
                        }
                        if (!interim) {
                            if (status != group.expectedStatus) throw HttpProbeException(status)
                            break
                        }
                    }
                    return (System.nanoTime() - begin) / 1_000_000L
                } finally {
                    // This one-shot HTTP connection is never reused. Close the underlying
                    // socket before TLS cleanup so close_notify cannot extend the probe deadline.
                    socket.close()
                }
            }
        }
    }
}
