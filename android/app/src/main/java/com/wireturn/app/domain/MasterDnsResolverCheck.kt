package com.wireturn.app.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.kernel.MasterDnsKernel
import com.wireturn.app.kernel.MemoryConfigs
import kotlinx.coroutines.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class MasterDnsResolverResult(
    val resolver: String,
    val domain: String,
    val valid: Boolean,
    val upload: Int,
    val download: Int,
    /** Median successful native tunnel-probe RTT, never the complete MTU sweep duration. */
    val latencyMs: Long,
    val attempts: Int = 0,
    val successes: Int = 0,
    val mtuCheckMs: Long = 0
) {
    val lossPercent: Double? get() = if (attempts > 0) 100.0 * (attempts - successes) / attempts else null
}

object MasterDnsResolverCheck {
    const val MAX_CANDIDATES = 128
    private const val FAST_WORKERS = 64
    private const val FAST_TIMEOUT_MS = 750
    private const val FAST_QUERY_INTERVAL_NS = 10_000_000L

    suspend fun scan(context: Context, config: MasterDnsConfig, onProgress: (String) -> Unit,
                     onResult: (MasterDnsResolverResult) -> Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = (listOfNotNull(manager.activeNetwork) + manager.allNetworks).distinct()
            .firstOrNull { isPhysicalNetwork(manager, it) } ?: error("Нет доступной Wi-Fi или мобильной сети")
        scan(context, config, network, config.resolverList(), onProgress, onResult)
    }

    suspend fun scan(
        context: Context,
        config: MasterDnsConfig,
        network: Network,
        candidates: List<String>,
        onProgress: (String) -> Unit,
        onResult: (MasterDnsResolverResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        // A resolver that answers one delegated zone need not answer another. Build
        // the catalogue shortlist independently before native probes for each domain.
        val failed = mutableListOf<String>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        for ((index, domain) in config.domains.distinct().withIndex()) {
            ensureActive()
            try {
                scanDomain(context, config.copy(domains = listOf(domain),
                    localResolvers = config.localResolverList(domain).joinToString("\n"),
                    localDomainResolvers = null, useLocalResolvers = false), network, candidates,
                    onProgress = { onProgress("${index + 1}/${config.domains.size} · $domain\n$it") },
                    onResult = onResult)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                check(isPhysicalNetwork(manager, network)) { "Выбранная сеть отключилась; сканирование остановлено" }
                failed += domain
            }
        }
        check(failed.isEmpty()) { "Не завершена проверка доменов: ${failed.joinToString()}. Завершённые пробы сохранены." }
    }

    private suspend fun scanDomain(
        context: Context,
        config: MasterDnsConfig,
        network: Network,
        candidates: List<String>,
        onProgress: (String) -> Unit,
        onResult: (MasterDnsResolverResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        check(isPhysicalNetwork(manager, network)) { "Выбранная Wi-Fi или мобильная сеть недоступна" }
        config.copy(resolvers = "192.0.2.1", localResolvers = null, useLocalResolvers = false).validate()
        val supplied = candidates.mapNotNull(MasterDnsEndpoint::parse).distinctBy { it.canonical }
        require(supplied.isNotEmpty() && supplied.size <= 102_048) { "Нужно до 100 000 адресов каталога и до 2048 адресов профиля" }
        val endpoints = (supplied + (config.localResolverList() + config.profileResolverList()).mapNotNull(MasterDnsEndpoint::parse))
            .distinctBy { it.canonical }
        val networkMonitor = launch(Dispatchers.Default) {
            while (isActive) {
                delay(400)
                check(isPhysicalNetwork(manager, network)) { "Выбранная сеть отключилась; сканирование остановлено" }
            }
        }
        try {
            val shortlist = if (endpoints.size <= MAX_CANDIDATES) endpoints else {
                withContext(Dispatchers.Main) { onProgress("Предварительный DNS-отбор ${endpoints.size} адресов; это ещё не проверка туннеля") }
                val preferred = (config.localResolverList() + config.profileResolverList()).mapNotNull(MasterDnsEndpoint::parse)
                    .map { it.canonical }.toSet()
                fastPass(network, endpoints, config.domains.first(), preferred, onProgress)
            }
            ensureActive()
            check(isPhysicalNetwork(manager, network)) { "Выбранная сеть отключилась; сканирование остановлено" }
            withContext(Dispatchers.Main) { onProgress("Проверка туннеля и MTU: ${shortlist.size} резолверов; затем 5 проб на каждую пару") }
            MasterDnsScanRelay(shortlist) { socket -> network.bindSocket(socket) }.use { relay ->
                val monitor = launch(Dispatchers.Default) {
                    while (isActive) {
                        delay(400)
                        relay.checkHealthy()
                    }
                }
                try {
                    val nativeConfig = config.copy(
                        domains = config.domains.map { it.lowercase(Locale.ROOT) }.distinct(),
                        resolvers = relay.resolverMapping.keys.joinToString("\n"),
                        localResolvers = null, useLocalResolvers = false,
                        settings = config.settings + mapOf("MTU_TEST_TIMEOUT" to "1.5", "MTU_TEST_RETRIES" to "1", "MTU_TEST_PARALLELISM" to "8")
                    )
                    nativeScan(context, nativeConfig, relay.resolverMapping, onProgress, onResult)
                } finally { monitor.cancelAndJoin() }
            }
        } finally { networkMonitor.cancelAndJoin() }
    }

    private fun isPhysicalNetwork(manager: ConnectivityManager, network: Network): Boolean =
        manager.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        } == true

    private data class Reachable(val endpoint: MasterDnsEndpoint, val elapsedMs: Long, val successfulCode: Boolean)

    private suspend fun fastPass(
        network: Network,
        endpoints: List<MasterDnsEndpoint>,
        domain: String,
        preferred: Set<String>,
        onProgress: (String) -> Unit
    ): List<MasterDnsEndpoint> = withTimeout(20 * 60_000L) {
        coroutineScope {
            val next = AtomicInteger()
            val done = AtomicInteger()
            val slot = AtomicLong(System.nanoTime())
            val open = ConcurrentHashMap.newKeySet<DatagramSocket>()
            val reachable = java.util.Collections.synchronizedList(mutableListOf<Reachable>())
            val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { open.forEach { it.close() } }
            }
            try {
                val workers = List(minOf(FAST_WORKERS, endpoints.size)) {
                    launch(Dispatchers.IO) {
                        val random = SecureRandom()
                        while (true) {
                            ensureActive()
                            val index = next.getAndIncrement()
                            if (index >= endpoints.size) break
                            val endpoint = endpoints[index]
                            val reserved = slot.getAndUpdate { previous -> maxOf(previous, System.nanoTime()) + FAST_QUERY_INTERVAL_NS }
                            val waitNs = reserved - System.nanoTime()
                            if (waitNs > 0) delay((waitNs + 999_999) / 1_000_000)
                            val query = MasterDnsScanWire.query(random.nextInt(65536), domain, random.nextInt().toUInt().toString(16))
                            val socket = DatagramSocket(null)
                            open += socket
                            var bound = false
                            try {
                                ensureActive()
                                network.bindSocket(socket)
                                bound = true
                                socket.soTimeout = FAST_TIMEOUT_MS
                                socket.connect(InetSocketAddress(endpoint.address, endpoint.port))
                                val started = System.nanoTime()
                                socket.send(DatagramPacket(query, query.size))
                                val deadline = started + FAST_TIMEOUT_MS * 1_000_000L
                                while (true) {
                                    ensureActive()
                                    val remaining = deadline - System.nanoTime()
                                    if (remaining <= 0) break
                                    socket.soTimeout = ((remaining + 999_999) / 1_000_000).toInt().coerceAtLeast(1)
                                    val packet = DatagramPacket(ByteArray(4096), 4096)
                                    socket.receive(packet)
                                    val response = packet.data.copyOf(packet.length)
                                    if (!MasterDnsScanWire.responseMatches(query, response)) continue
                                    val rcode = response[3].toInt() and 15
                                    reachable += Reachable(endpoint, (System.nanoTime() - started) / 1_000_000, rcode == 0 || rcode == 3)
                                    break
                                }
                            } catch (error: java.io.IOException) {
                                ensureActive()
                                if (!bound) throw java.io.IOException("Не удалось привязать DNS-пробу к выбранной сети", error)
                            } finally { open -= socket; socket.close() }
                            val completed = done.incrementAndGet()
                            if (completed % 100 == 0 || completed == endpoints.size)
                                withContext(Dispatchers.Main) { onProgress("DNS-отбор: $completed/${endpoints.size}; ответили ${reachable.size}") }
                        }
                    }
                }
                workers.joinAll()
                val selected = endpoints.filter { it.canonical in preferred }.take(32).toMutableList()
                val ranked = reachable.sortedWith(compareByDescending<Reachable> { it.successfulCode }.thenBy { it.elapsedMs })
                for (item in ranked) {
                    if (selected.size >= MAX_CANDIDATES) break
                    if (selected.none { it.canonical == item.endpoint.canonical }) selected += item.endpoint
                }
                // A DNS timeout is not proof of tunnel failure; sample nonresponders in unused capacity.
                for (endpoint in endpoints) {
                    if (selected.size >= MAX_CANDIDATES) break
                    if (selected.none { it.canonical == endpoint.canonical }) selected += endpoint
                }
                withContext(Dispatchers.Main) {
                    onProgress("DNS-отбор завершён: ${endpoints.size}; для туннеля выбрано ${selected.size}. Остальные не проверены на совместимость.")
                }
                selected
            } finally {
                closer.cancelAndJoin()
                open.forEach { it.close() }
            }
        }
    }

    private suspend fun nativeScan(
        context: Context,
        config: MasterDnsConfig,
        resolverMapping: Map<String, String>,
        onProgress: (String) -> Unit,
        onResult: (MasterDnsResolverResult) -> Unit
    ) = withTimeout(200_000L) {
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "masterdns-scan-").toFile()
        var process: Process? = null
        var killer: Job? = null
        try {
            val file = MasterDnsKernel.writeConfig(dir, config, "127.0.0.1:18000", UUID.randomUUID().toString(), UUID.randomUUID().toString(), true)
            val proc = MemoryConfigs.start(ProcessBuilder(
                "${context.applicationInfo.nativeLibraryDir}/libmasterdns.so", "-config", file.absolutePath,
                "-resolvers", File(dir, "client_resolvers.txt").absolutePath, "-scan"
            ).directory(dir).redirectErrorStream(true))
            process = proc
            killer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { proc.destroyForcibly() }
            }
            var completed = false
            var received = 0
            val seen = HashSet<Pair<String, String>>()
            val gson = Gson()
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ensureActive()
                    if (line.startsWith("TUNA_RESOLVER ")) {
                        val raw = gson.fromJson(line.substringAfter(' '), MasterDnsResolverResult::class.java)
                        val resolver = requireNotNull(resolverMapping[raw.resolver]) { "Ядро вернуло неизвестный резолвер" }
                        require(raw.domain in config.domains && seen.add(resolver to raw.domain)) { "Повторный или неизвестный результат ядра" }
                        require(raw.attempts in 0..5 && raw.successes in 0..raw.attempts && raw.latencyMs >= 0 && raw.mtuCheckMs >= 0)
                        require(!raw.valid || raw.attempts == 5 && raw.successes > 0 && raw.upload > 0 && raw.download > 0) { "Ядро не подтвердило пять повторных туннельных проб" }
                        val result = raw.copy(resolver = resolver)
                        received++
                        withContext(Dispatchers.Main) {
                            onResult(result)
                            onProgress("Туннель: $received/${resolverMapping.size * config.domains.size} пар проверено")
                        }
                    } else if (line == "TUNA_SCAN_DONE") completed = true
                }
            }
            ensureActive()
            check(completed && proc.waitFor() == 0) { "Проверка ядра не завершена или истекли 3 минуты; полученные результаты частичные" }
            check(received == resolverMapping.size * config.domains.size) { "Ядро вернуло неполную проверку" }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.destroy()
                if (process?.waitFor(1, TimeUnit.SECONDS) == false) process?.destroyForcibly()
                killer?.cancelAndJoin()
                process?.inputStream?.close()
                process?.outputStream?.close()
                MemoryConfigs.removeDirectory(dir)
                dir.deleteRecursively()
            }
        }
    }
}
