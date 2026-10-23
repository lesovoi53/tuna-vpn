package com.wireturn.app.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.gson.JsonParser
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.kernel.MasterDnsKernel
import com.wireturn.app.kernel.MemoryConfigs
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class MasterDnsGoodputLimits(
    val url: String = "https://speed.cloudflare.com/__down?bytes=262144",
    val maxPayloadBytes: Long = 262144,
    val totalTimeoutMs: Long = 180000,
    val maxWireBytes: Long = 8L * 1024 * 1024,
    val socketTimeoutMs: Int = 30000
) {
    init {
        require(maxPayloadBytes in 1..262144 && totalTimeoutMs in 1..210000)
        require(maxWireBytes in 1..(16L * 1024 * 1024) && socketTimeoutMs in 1..60000)
        MasterDnsGoodput.validateUrl(url)
    }
}

data class MasterDnsGoodputProgress(
    val phase: String,
    val payloadBytes: Long,
    val wireBytes: Long,
    val effectiveParameters: String?,
    val maxPayloadBytes: Long,
    val maxWireBytes: Long
)

/** A complete HTTPS response through this entire resolver pool, never a per-resolver estimate. */
data class MasterDnsGoodputResult(
    val resolvers: List<String>,
    val payloadBytes: Long,
    val elapsedMs: Long,
    val wireBytes: Long,
    val effectiveParameters: String
) {
    val goodputBytesPerSecond: Double get() = payloadBytes * 1000.0 / elapsedMs.coerceAtLeast(1)
}

/**
 * Separate ephemeral native process, with no service, active-profile or default-network mutation.
 * Destination DNS stays in the authenticated SOCKS tunnel. TLS uses the platform trust roots and
 * HTTPS endpoint identification. Every failure is terminal; there is no direct-route retry.
 */
object MasterDnsGoodput {
    private val loopback: InetAddress get() = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    suspend fun measure(
        context: Context,
        config: MasterDnsConfig,
        network: Network,
        resolvers: List<String>,
        limits: MasterDnsGoodputLimits = MasterDnsGoodputLimits(),
        onProgress: (MasterDnsGoodputProgress) -> Unit = {}
    ): MasterDnsGoodputResult = withTimeout(limits.totalTimeoutMs) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                fun checkNetwork() {
                    val caps = manager.getNetworkCapabilities(network)
                    check(caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) {
                        "Выбранная Wi-Fi или мобильная сеть недоступна"
                    }
                }
                checkNetwork()
                require(limits.maxPayloadBytes >= 16 * 1024) { "Для сравнения нужен лимит не менее 16 КиБ" }
                val endpoints = resolvers.map { requireNotNull(MasterDnsEndpoint.parse(it)) { "Некорректный резолвер" } }
                    .distinctBy { it.canonical }
                require(endpoints.size in 1..128)
                config.copy(resolvers = endpoints.joinToString("\n") { it.canonical },
                    localResolvers = null, localDomainResolvers = null, useLocalResolvers = false).validate()
                MasterDnsScanRelay(endpoints, byteBudget = limits.maxWireBytes) { network.bindSocket(it) }.use { relay ->
                    val effective = AtomicReference<String?>(null)
                    val transferred = java.util.concurrent.atomic.AtomicLong()
                    suspend fun report(phase: String) = withContext(Dispatchers.Main) {
                        onProgress(MasterDnsGoodputProgress(phase, transferred.get(), relay.wireBytes,
                            effective.get(), limits.maxPayloadBytes, limits.maxWireBytes))
                    }
                    val networkMonitor = launch(Dispatchers.Default) {
                        while (isActive) {
                            delay(200)
                            checkNetwork()
                            relay.checkHealthy()
                        }
                    }
                    val directory = Files.createTempDirectory(context.cacheDir.toPath(), "masterdns-goodput-").toFile()
                    var process: Process? = null
                    var killer: Job? = null
                    var reader: Job? = null
                    try {
                        val port = ServerSocket(0, 1, loopback).use { it.localPort }
                        val user = UUID.randomUUID().toString()
                        val password = UUID.randomUUID().toString()
                        val isolated = config.copy(
                            domains = config.domains.map { it.lowercase(Locale.ROOT) }.distinct(),
                            resolvers = relay.resolverMapping.keys.joinToString("\n"),
                            localResolvers = null, localDomainResolvers = null, useLocalResolvers = false,
                            settings = config.settings + mapOf("SAVE_MTU_SERVERS_TO_FILE" to "false", "LOG_LEVEL" to "ERROR")
                        )
                        val file = MasterDnsKernel.writeConfig(directory, isolated, "127.0.0.1:$port", user, password, true)
                        report("Подключение отдельной тестовой сессии")
                        ensureActive()
                        val proc = MemoryConfigs.start(ProcessBuilder(
                            "${context.applicationInfo.nativeLibraryDir}/libmasterdns.so", "-config", file.absolutePath,
                            "-resolvers", File(directory, "client_resolvers.txt").absolutePath, "-diagnostic"
                        ).directory(directory).redirectErrorStream(true))
                        process = proc
                        killer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { proc.destroyForcibly() }
                        }
                        val ready = CompletableDeferred<Unit>()
                        val started = AtomicBoolean()
                        reader = launch(Dispatchers.IO) {
                            try {
                                proc.inputStream.bufferedReader().use { input ->
                                    while (true) {
                                        ensureActive()
                                        val line = readBoundedLine(input) ?: break
                                        when {
                                            line.startsWith("TUNA_MASTER_POLICY ") -> effective.set(validatePolicy(line.substringAfter(' '), endpoints.size))
                                            line == "TUNA_MASTER_READY" -> {
                                                check(effective.get() != null) { "Ядро не подтвердило параметры тестовой сессии" }
                                                started.set(true)
                                                ready.complete(Unit)
                                            }
                                            line == "TUNA_MASTER_CONNECTING" && started.get() -> error("Тестовая сессия переподключилась; результат неполный")
                                            line.startsWith("TUNA_MASTER_ERROR") -> error("MasterDNS не смог запустить тестовую сессию")
                                        }
                                    }
                                }
                            } catch (error: Throwable) {
                                ensureActive() // Closing a cancelled process pipe is cancellation, not a new failure.
                                throw error
                            }
                            ensureActive()
                            error("Тестовая сессия MasterDNS завершилась до окончания замера")
                        }
                        ready.await()
                        report("HTTPS-передача через пул ${endpoints.size} резолверов")
                        val ticker = launch(Dispatchers.Default) {
                            while (isActive) { delay(500); report("HTTPS-передача через пул ${endpoints.size} резолверов") }
                        }
                        val sample = try {
                            transfer(port, user, password, limits, onPayload = { transferred.set(it) })
                        } finally { ticker.cancelAndJoin() }
                        ensureActive()
                        checkNetwork()
                        relay.checkHealthy()
                        require(sample.payloadBytes >= 16 * 1024) { "Для сравнения нужно не менее 16 КиБ полезной нагрузки" }
                        val result = MasterDnsGoodputResult(endpoints.map { it.canonical }, sample.payloadBytes, sample.elapsedMs,
                            relay.wireBytes, requireNotNull(effective.get()))
                        report("Замер пула завершён")
                        result
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            // Cancel the reader before intentionally terminating its process.
                            reader?.cancel()
                            killer?.cancelAndJoin()
                            process?.destroy()
                            if (process?.waitFor(1, TimeUnit.SECONDS) == false) {
                                process.destroyForcibly()
                                process.waitFor(1, TimeUnit.SECONDS)
                            }
                            reader?.join()
                            process?.inputStream?.close()
                            process?.outputStream?.close()
                            networkMonitor.cancelAndJoin()
                            MemoryConfigs.removeDirectory(directory)
                            directory.deleteRecursively()
                        }
                    }
                }
            }
        }
    }

    internal data class Transfer(val payloadBytes: Long, val elapsedMs: Long)

    /** The only injection is scoped TLS trust in synthetic tests; production cannot replace routing. */
    internal suspend fun transfer(
        port: Int, user: String, password: String, limits: MasterDnsGoodputLimits,
        tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
        onPayload: (Long) -> Unit = {}
    ): Transfer = withTimeout(limits.totalTimeoutMs) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                require(port in 1..65535)
                val uri = validateUrl(limits.url)
                val socket = Socket()
                val tlsRef = AtomicReference<SSLSocket?>()
                val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally {
                        // Closing raw transport first interrupts even a blocked TLS close/read.
                        runCatching { socket.close() }
                        runCatching { tlsRef.get()?.close() }
                    }
                }
                try {
                    ensureActive()
                    socket.soTimeout = limits.socketTimeoutMs
                    socket.connect(InetSocketAddress(loopback, port), limits.socketTimeoutMs)
                    authenticateAndConnect(socket, user, password, uri)
                    ensureActive()
                    val host = uri.host.removeSurrounding("[", "]")
                    val tls = tlsFactory.createSocket(socket, host, if (uri.port == -1) 443 else uri.port, true) as SSLSocket
                    tlsRef.set(tls)
                    ensureActive()
                    tls.soTimeout = limits.socketTimeoutMs
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    val authority = if (uri.port == -1 || uri.port == 443) uri.host else "${uri.host}:${uri.port}"
                    val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                    // Include response wait/headers: TLS may buffer body bytes while headers
                    // are read. Starting afterwards would overstate short-response goodput.
                    val began = System.nanoTime()
                    tls.outputStream.write(("GET $path HTTP/1.1\r\nHost: $authority\r\nAccept-Encoding: identity\r\n" +
                        "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    tls.outputStream.flush()
                    val input = tls.inputStream
                    val header = readHeaders(input)
                    require(header.first == "200") { "HTTPS-источник не вернул 200; перенаправления запрещены" }
                    require(header.second["content-encoding"]?.lowercase(Locale.ROOT) in listOf(null, "identity")) { "Сжатый HTTP-ответ не поддерживается" }
                    val length = header.second["content-length"]?.toLongOrNull()
                    val chunked = header.second["transfer-encoding"]?.lowercase(Locale.ROOT)
                    require(chunked == null || chunked == "chunked") { "Некорректный HTTP Transfer-Encoding" }
                    require(chunked == null || length == null) { "Неоднозначная длина HTTP-ответа" }
                    require(length == null || length in 1..limits.maxPayloadBytes) { "HTTPS-ответ превышает лимит полезной нагрузки" }
                    require(length != null || chunked == "chunked") { "HTTPS-ответ должен содержать ограниченную длину" }
                    var bytes = 0L
                    val buffer = ByteArray(8192)
                    fun readPayload(count: Long) {
                        require(count <= limits.maxPayloadBytes - bytes) { "HTTPS-ответ превышает лимит полезной нагрузки" }
                        var remaining = count
                        while (remaining > 0) {
                            ensureActive()
                            val got = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (got < 0) throw EOFException("Неполный HTTPS-ответ")
                            bytes += got
                            remaining -= got
                            onPayload(bytes)
                        }
                    }
                    if (length != null) readPayload(length) else {
                        var chunks = 0
                        while (true) {
                            ensureActive()
                            require(++chunks <= limits.maxPayloadBytes + 1) { "Слишком много HTTP-частей" }
                            val raw = readHttpLine(input, 1024)
                            require(raw.matches(Regex("[0-9a-fA-F]{1,8}"))) { "Некорректная длина HTTP-части" }
                            val count = raw.toLong(16)
                            if (count == 0L) {
                                require(readHttpLine(input, 1024).isEmpty()) { "HTTP trailers не поддерживаются" }
                                break
                            }
                            readPayload(count)
                            require(readHttpLine(input, 2).isEmpty()) { "Некорректная HTTP-часть" }
                        }
                    }
                    require(bytes > 0) { "Пустая полезная нагрузка" }
                    ensureActive()
                    Transfer(bytes, ((System.nanoTime() - began) / 1_000_000).coerceAtLeast(1))
                } catch (error: Throwable) {
                    // A Java read interrupted by our socket closer throws IOException. Preserve
                    // the coroutine's timeout/user-cancellation instead of replacing its cause.
                    ensureActive()
                    throw error
                } finally {
                    withContext(NonCancellable) { closer.cancelAndJoin() }
                }
            }
        }
    }

    internal fun validateUrl(value: String): URI {
        require(value.length in 1..2048 && value.all { it.code in 33..126 }) { "Некорректный HTTPS URL" }
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && uri.host.length <= 253) { "Нужен HTTPS URL без учётных данных и фрагмента" }
        return uri
    }

    private fun authenticateAndConnect(socket: Socket, user: String, password: String, uri: URI) {
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream
        val username = user.toByteArray(Charsets.UTF_8)
        val secret = password.toByteArray(Charsets.UTF_8)
        require(username.size in 1..255 && secret.size in 1..255)
        output.write(byteArrayOf(5, 1, 2))
        output.flush()
        require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2) { "SOCKS-аутентификация обязательна" }
        output.write(byteArrayOf(1, username.size.toByte()) + username + byteArrayOf(secret.size.toByte()) + secret)
        output.flush()
        require(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "SOCKS-аутентификация отклонена" }
        val hostname = uri.host.removeSurrounding("[", "]").toByteArray(Charsets.US_ASCII)
        require(hostname.size in 1..255)
        val destinationPort = if (uri.port == -1) 443 else uri.port
        // ATYP=domain deliberately avoids local DNS, including for ordinary hostnames.
        output.write(byteArrayOf(5, 1, 0, 3, hostname.size.toByte()) + hostname +
            byteArrayOf((destinationPort shr 8).toByte(), destinationPort.toByte()))
        output.flush()
        require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0 && input.readUnsignedByte() == 0) {
            "SOCKS-туннель не подключился к HTTPS-источнику"
        }
        val addressBytes = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> error("Некорректный SOCKS-ответ")
        }
        input.readFully(ByteArray(addressBytes + 2))
    }

    private fun readHeaders(input: InputStream): Pair<String, Map<String, String>> {
        val first = readHttpLine(input, 4096)
        require(first.matches(Regex("HTTP/1\\.[01] [0-9]{3}(?: [\\x20-\\x7e]*)?"))) { "Некорректный HTTP-статус" }
        val headers = mutableMapOf<String, String>()
        var size = first.length
        while (true) {
            val line = readHttpLine(input, 4096)
            size += line.length + 2
            require(size <= 16384) { "Слишком большие HTTPS-заголовки" }
            if (line.isEmpty()) break
            val key = line.substringBefore(':').lowercase(Locale.ROOT)
            require(':' in line && key.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+"))) { "Некорректный HTTP-заголовок" }
            val value = line.substringAfter(':').trim()
            if (key in setOf("content-length", "transfer-encoding", "content-encoding")) {
                require(key !in headers) { "Повторный заголовок длины/кодирования" }
                if (key == "content-length") require(value.matches(Regex("[0-9]{1,12}"))) { "Некорректная длина HTTPS-ответа" }
            }
            headers[key] = value
        }
        return first.substring(9, 12) to headers
    }

    private fun readHttpLine(input: InputStream, limit: Int): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) throw EOFException("Неполный HTTP-заголовок")
            if (byte == 13) {
                require(input.read() == 10) { "Некорректный конец HTTP-строки" }
                return line.toString()
            }
            require(byte in 32..126 && line.length < limit) { "Некорректная или слишком длинная HTTP-строка" }
            line.append(byte.toChar())
        }
    }

    private fun readBoundedLine(input: java.io.Reader): String? {
        val line = StringBuilder()
        while (true) {
            val char = input.read()
            if (char == -1) return line.toString().takeIf { it.isNotEmpty() }
            if (char == 10) return line.toString().removeSuffix("\r")
            require(line.length < 8192) { "Слишком длинный ответ ядра" }
            line.append(char.toChar())
        }
    }

    internal fun validatePolicy(json: String, resolverCount: Int): String {
        val allowed = setOf("resolverCount", "strategy", "packetDuplication", "setupDuplication", "rxTxWorkers",
            "processWorkers", "maxUploadMtu", "maxDownloadMtu", "batch", "window", "dataNackGap",
            "compressionMinSize", "initialRtoSeconds", "controlInitialRtoSeconds", "pingSeconds")
        val policy = JsonParser.parseString(json).asJsonObject
        require(policy.keySet() == allowed && policy.entrySet().all { (_, value) ->
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asDouble.let { it.isFinite() && it >= 0 }
        }) { "Некорректные параметры диагностической сессии" }
        require(policy["resolverCount"].asInt == resolverCount) { "Ядро потеряло резолверы тестового пула" }
        return policy.toString()
    }
}
