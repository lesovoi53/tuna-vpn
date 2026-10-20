package com.wireturn.app.domain

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Numeric endpoints only. Parsing and address conversion never perform a DNS lookup. */
@ConsistentCopyVisibility
data class MasterDnsEndpoint private constructor(val host: String, val port: Int) {
    val canonical: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
    val value: String get() = canonical
    val address: InetAddress get() = InetAddress.getByAddress(literalBytes(host)!!)

    companion object {
        fun parse(value: String): MasterDnsEndpoint? {
            val text = value.trim()
            if (text.isEmpty() || text.length > 80 || text.any { it.isWhitespace() }) return null
            val host: String
            val portText: String?
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                host = text.substring(1, close)
                if (':' !in host) return null
                val tail = text.substring(close + 1)
                if (tail.isNotEmpty() && !tail.startsWith(":")) return null
                portText = if (tail.isEmpty()) null else tail.drop(1)
            } else if (text.count { it == ':' } == 1) {
                host = text.substringBefore(':')
                portText = text.substringAfter(':')
            } else {
                host = text
                portText = null
            }
            val port = if (portText == null) 53 else {
                if (portText.isEmpty() || portText.length > 5 || portText.any { it !in '0'..'9' }) return null
                portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
            val bytes = literalBytes(host) ?: return null
            val mapped = bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
            val normalized = if (bytes.size == 4 || mapped) {
                bytes.takeLast(4).joinToString(".") { (it.toInt() and 255).toString() }
            } else {
                (0 until 8).joinToString(":") {
                    (((bytes[it * 2].toInt() and 255) shl 8) or (bytes[it * 2 + 1].toInt() and 255)).toString(16)
                }
            }
            return MasterDnsEndpoint(normalized, port)
        }

        private fun ipv4(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for ((index, part) in parts.withIndex()) {
                // Reject ambiguous octal-looking notation rather than reinterpret it.
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                    (part.length > 1 && part.startsWith("0"))) return null
                bytes[index] = (part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toByte()
            }
            return bytes
        }

        private fun literalBytes(text: String): ByteArray? {
            if (':' !in text) return ipv4(text)
            if (text.any { it !in "0123456789abcdefABCDEF:." }) return null
            var expanded = text
            if ('.' in expanded) {
                val tail = expanded.substringAfterLast(':')
                val bytes = ipv4(tail) ?: return null
                val high = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
                val low = ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
                expanded = expanded.substringBeforeLast(':') + ":" + high.toString(16) + ":" + low.toString(16)
            }
            if (":::" in expanded || expanded.indexOf("::") != expanded.lastIndexOf("::")) return null
            val compressed = "::" in expanded
            fun groups(part: String): List<String> = if (part.isEmpty()) emptyList() else part.split(':')
            val left = groups(if (compressed) expanded.substringBefore("::") else expanded)
            val right = if (compressed) groups(expanded.substringAfter("::")) else emptyList()
            val missing = 8 - left.size - right.size
            if (if (compressed) missing < 1 else missing != 0) return null
            val groups = left + List(missing) { "0" } + right
            val result = ByteArray(16)
            for ((index, group) in groups.withIndex()) {
                if (group.isEmpty() || group.length > 4) return null
                val number = group.toIntOrNull(16) ?: return null
                result[index * 2] = (number ushr 8).toByte()
                result[index * 2 + 1] = number.toByte()
            }
            return result
        }
    }
}

data class MasterDnsCatalogSource(val name: String, val url: String)
data class MasterDnsCatalogSourceResult(
    val name: String,
    val url: String,
    val count: Int,
    val rejected: Int = 0,
    val error: String? = null,
)
data class MasterDnsCatalogSnapshot(
    val endpoints: List<MasterDnsEndpoint>,
    val sources: List<MasterDnsCatalogSourceResult>,
    val updatedAt: Long,
) {
    val count: Int get() = endpoints.size
    val partial: Boolean get() = sources.any { it.error != null || it.rejected > 0 }
}
data class MasterDnsCatalogProgress(val source: String, val completed: Int, val total: Int)
data class MasterDnsCatalogParseResult(val endpoints: List<MasterDnsEndpoint>, val rejected: Int)

/**
 * A candidate catalogue, not a list of verified working resolvers. No request happens at construction
 * or load; callers explicitly invoke update. Store under app filesDir for offline availability.
 */
class MasterDnsCatalog(
    private val cacheDirectory: File,
    private val sources: List<MasterDnsCatalogSource> = DEFAULT_SOURCES,
    private val download: suspend (String) -> ByteArray = { downloadHttps(it) },
) {
    private val mutex = Mutex()
    private val cacheFile get() = File(cacheDirectory, "catalog-v1.bin")

    suspend fun load(): MasterDnsCatalogSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!cacheFile.exists()) return@withLock null
            require(cacheFile.length() <= MAX_BYTES) { "Файл каталога слишком большой" }
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                require(input.readInt() == CACHE_MAGIC) { "Неизвестный формат каталога" }
                val updatedAt = input.readLong()
                val sourceCount = input.readInt()
                require(sourceCount in 1..32) { "Повреждены источники каталога" }
                val results = List(sourceCount) {
                    val name = input.readUTF()
                    val url = input.readUTF()
                    val count = input.readInt()
                    val rejected = input.readInt()
                    val error = input.readUTF().ifEmpty { null }
                    require(count in 0..MAX_ENDPOINTS && rejected >= 0) { "Повреждён каталог" }
                    MasterDnsCatalogSourceResult(name, url, count, rejected, error)
                }
                val count = input.readInt()
                require(count in 1..MAX_ENDPOINTS) { "Повреждён размер каталога" }
                val endpoints = LinkedHashSet<MasterDnsEndpoint>()
                repeat(count) {
                    currentCoroutineContext().ensureActive()
                    endpoints += requireNotNull(MasterDnsEndpoint.parse(input.readUTF())) { "Повреждён адрес каталога" }
                }
                require(input.read() == -1 && endpoints.size == count) { "Повреждён каталог" }
                MasterDnsCatalogSnapshot(endpoints.toList(), results, updatedAt)
            }
        }
    }

    suspend fun update(onProgress: (MasterDnsCatalogProgress) -> Unit = {}): MasterDnsCatalogSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(sources.isNotEmpty() && sources.size <= 32) { "Не заданы источники каталога" }
                val endpoints = LinkedHashSet<MasterDnsEndpoint>()
                val results = mutableListOf<MasterDnsCatalogSourceResult>()
                var bytesRead = 0
                for ((index, source) in sources.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    onProgress(MasterDnsCatalogProgress(source.name, index, sources.size))
                    try {
                        require(source.url.startsWith("https://")) { "Требуется HTTPS" }
                        val bytes = download(source.url)
                        require(bytes.size <= MAX_BYTES - bytesRead) { "Превышен размер каталога" }
                        bytesRead += bytes.size
                        val parsed = parse(bytes)
                        require(parsed.endpoints.isNotEmpty()) { "В источнике нет допустимых адресов" }
                        val added = parsed.endpoints.count { it !in endpoints }
                        require(endpoints.size + added <= MAX_ENDPOINTS) { "Слишком много адресов" }
                        endpoints += parsed.endpoints
                        results += MasterDnsCatalogSourceResult(source.name, source.url, parsed.endpoints.size, parsed.rejected)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Do not surface request headers, endpoint strings or exception payloads.
                        results += MasterDnsCatalogSourceResult(source.name, source.url, 0, error = "Не удалось загрузить или разобрать источник")
                    }
                    onProgress(MasterDnsCatalogProgress(source.name, index + 1, sources.size))
                }
                require(endpoints.isNotEmpty()) { "Все источники недоступны или пусты. Предыдущий каталог сохранён." }
                val snapshot = MasterDnsCatalogSnapshot(endpoints.toList(), results, System.currentTimeMillis())
                save(snapshot)
                snapshot
            }
        }

    /** The caller owns the input stream; suitable for Android's document picker and offline backups. */
    suspend fun importList(input: InputStream): MasterDnsCatalogSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = currentCoroutineContext()
            val parsed = parse(readBounded(input) { context.ensureActive() })
            require(parsed.endpoints.isNotEmpty()) { "В файле нет допустимых DNS-адресов" }
            val snapshot = MasterDnsCatalogSnapshot(
                parsed.endpoints,
                listOf(MasterDnsCatalogSourceResult("Локальный файл", "", parsed.endpoints.size, parsed.rejected)),
                System.currentTimeMillis(),
            )
            save(snapshot)
            snapshot
        }
    }

    /** Plain UTF-8 backup, accepted by importList. Does not close the caller's output stream. */
    suspend fun exportList(snapshot: MasterDnsCatalogSnapshot, output: OutputStream) = withContext(Dispatchers.IO) {
        output.write("# TUNA MasterDNS candidate catalogue; updatedAt=${snapshot.updatedAt}\n".toByteArray(Charsets.UTF_8))
        for (endpoint in snapshot.endpoints) {
            currentCoroutineContext().ensureActive()
            output.write("${endpoint.canonical}\n".toByteArray(Charsets.UTF_8))
        }
        output.flush()
    }

    private suspend fun save(snapshot: MasterDnsCatalogSnapshot) {
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Не удалось открыть хранилище каталога" }
        val temporary = File.createTempFile("catalog-", ".tmp", cacheDirectory)
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(file.buffered())
                output.writeInt(CACHE_MAGIC)
                output.writeLong(snapshot.updatedAt)
                output.writeInt(snapshot.sources.size)
                for (source in snapshot.sources) {
                    output.writeUTF(source.name)
                    output.writeUTF(source.url)
                    output.writeInt(source.count)
                    output.writeInt(source.rejected)
                    output.writeUTF(source.error.orEmpty())
                }
                output.writeInt(snapshot.count)
                for (endpoint in snapshot.endpoints) {
                    currentCoroutineContext().ensureActive()
                    output.writeUTF(endpoint.canonical)
                }
                output.flush()
                require(temporary.length() <= MAX_BYTES) { "Каталог слишком большой" }
                file.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            // Atomic replacement keeps the old cache intact on failed writes or interrupted downloads.
            Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_ENDPOINTS = 100_000
        private const val CACHE_MAGIC = 0x4d444e31
        val DEFAULT_SOURCES = listOf(
            MasterDnsCatalogSource("Trickest resolvers", "https://raw.githubusercontent.com/trickest/resolvers/main/resolvers.txt"),
            MasterDnsCatalogSource("MassDNS resolvers", "https://raw.githubusercontent.com/blechschmidt/massdns/master/lists/resolvers.txt"),
        )

        /** Line based UTF-8: literal IP[:port], blank lines and # comments. Invalid rows are counted. */
        fun parse(bytes: ByteArray): MasterDnsCatalogParseResult {
            require(bytes.size <= MAX_BYTES) { "Файл каталога превышает 8 МиБ" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val endpoints = LinkedHashSet<MasterDnsEndpoint>()
            var rejected = 0
            for (line in text.removePrefix("\uFEFF").lineSequence()) {
                val candidate = line.substringBefore('#').trim()
                if (candidate.isEmpty()) continue
                val endpoint = MasterDnsEndpoint.parse(candidate)
                if (endpoint == null) rejected++ else endpoints += endpoint
                require(endpoints.size <= MAX_ENDPOINTS) { "В каталоге больше 100 000 адресов" }
            }
            return MasterDnsCatalogParseResult(endpoints.toList(), rejected)
        }

        private fun readBounded(input: InputStream, checkCancelled: () -> Unit): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Файл каталога превышает 8 МиБ" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        }

        internal suspend fun downloadHttps(url: String, downloadClient: OkHttpClient = client): ByteArray = coroutineScope {
            var current = url
            for (redirect in 0..3) {
                val request = Request.Builder().url(current).get().header("Accept", "text/plain").build()
                require(request.url.isHttps && request.url.username.isEmpty() && request.url.password.isEmpty()) { "Требуется HTTPS без учётных данных" }
                val call = downloadClient.newCall(request)
                val canceller = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            require(redirect < 3) { "Слишком много перенаправлений" }
                            val location = response.header("Location") ?: error("Нет адреса перенаправления")
                            val next = request.url.resolve(location) ?: error("Неверный адрес перенаправления")
                            require(next.isHttps && next.username.isEmpty() && next.password.isEmpty()) { "Небезопасное перенаправление" }
                            current = next.toString()
                        } else {
                            check(response.isSuccessful) { "Источник недоступен" }
                            val body = response.body ?: error("Пустой ответ источника")
                            require(body.contentLength() <= MAX_BYTES) { "Источник слишком большой" }
                            val context = currentCoroutineContext()
                            return@coroutineScope readBounded(body.byteStream()) { context.ensureActive() }
                        }
                    }
                } finally {
                    withContext(NonCancellable) { canceller.cancelAndJoin() }
                }
            }
            error("Слишком много перенаправлений")
        }
    }
}
