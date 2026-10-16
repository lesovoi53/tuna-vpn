package com.wireturn.app.domain

import android.content.Context
import android.net.Network
import com.wireturn.app.data.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class CloudflareScanner(private val context: Context, private val network: Network) {
    suspend fun ranges(refresh: Boolean = true): List<CfAddress.Range> = withContext(Dispatchers.IO) {
        val cache = context.getSharedPreferences("cf_range_cache", Context.MODE_PRIVATE)
        val values = listOf(4, 6).flatMap { version ->
            currentCoroutineContext().ensureActive()
            val key = "v$version"
            var text: String? = cache.getString(key, null)
            if (refresh && (System.currentTimeMillis() - cache.getLong("time$key", 0) > 86_400_000L || text == null)) {
                try {
                    val connection = network.openConnection(URL("https://www.cloudflare.com/ips-v$version")) as HttpURLConnection
                    connection.connectTimeout = 4000; connection.readTimeout = 4000
                    connection.instanceFollowRedirects = false
                    try {
                        val fresh = coroutineScope {
                            val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                                try { awaitCancellation() } finally { connection.disconnect() }
                            }
                            try {
                                require(connection.responseCode == 200)
                                connection.inputStream.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(1024)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        require(output.size() + read <= 16_384)
                                        output.write(buffer, 0, read)
                                    }
                                    output.toString("US-ASCII")
                                }
                            } finally { closer.cancelAndJoin() }
                        }
                        val parsed = fresh.trim().split(Regex("\\s+"))
                        require(parsed.size in 1..100 && parsed.all { CfAddress.range(it)?.bits == if (version == 4) 32 else 128 })
                        text = fresh
                        cache.edit().putString(key, fresh).putLong("time$key", System.currentTimeMillis()).apply()
                    } finally { connection.disconnect() }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Cached/bundled ranges keep offline startup non-fatal. */ }
            }
            text?.trim()?.split(Regex("\\s+"))?.mapNotNull { CfAddress.range(it) }
                ?.takeIf { it.isNotEmpty() } ?: CfAddress.bundled.filter { it.bits == if (version == 4) 32 else 128 }
        }
        values
    }

    private fun connect(socket: Socket, address: String, port: Int, timeout: Int) {
        val ip = CfAddress.literal(address) ?: network.getAllByName(address).first()
        socket.connect(InetSocketAddress(ip, port), timeout)
        socket.tcpNoDelay = true
    }

    suspend fun scan(plan: CfProfilePlan, candidates: List<String>, settings: CfSettings,
                     onResult: suspend (CfResult) -> Unit, onProgress: suspend (CfScanProgress) -> Unit): CfScanOutcome =
        CfScanPipeline().run(candidates, settings,
            tcp = { ip -> preflight(ip, plan.port) },
            probe = { ip, stable -> probe(plan, ip, settings, stable) },
            onResult = onResult, onProgress = onProgress)

    private suspend fun preflight(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        // Connect on the physical Network; close the blocking socket promptly on cancellation.
        val socket = network.socketFactory.createSocket()
        coroutineScope {
            val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { socket.close() }
            }
            try {
                val address = CfAddress.literal(ip) ?: return@coroutineScope false
                socket.connect(InetSocketAddress(address, port), 2000)
                true
            } finally { socket.close(); closer.cancelAndJoin() }
        }
    }

    private suspend fun probe(plan: CfProfilePlan, ip: String, settings: CfSettings, stability: Boolean): CfResult = withContext(Dispatchers.IO) {
        var series = CfProbeSeries.Result(emptyList(), 0)
        var clean = false
        val timeout = settings.timeoutSeconds * 1000
        val stage = "HTTPS"
        try {
            // Bound the *whole* candidate, including core readiness. Cleanup always joins the child.
            withTimeout(20_000L + settings.samples * (timeout + 1000L)) {
                val relay = CfTcpRelay({ network.socketFactory.createSocket() }) { connect(it, ip, plan.port, timeout) }
                var down: CfTcpRelay? = null
                try {
                    down = plan.download?.let { target -> CfTcpRelay({ network.socketFactory.createSocket() }) { connect(it, target.host, target.port, timeout) } }
                    IsolatedProfileTest(context).withTunnel(plan.testProfile(relay.port, down?.port)) { config ->
                        val group = ConnectionGroup("cf-probe", "cf-probe", ConnectionCategory.VPN, GroupTestType.URL_TEST,
                            emptyList(), timeoutSeconds = settings.timeoutSeconds, samples = 1)
                        series = CfProbeSeries.measure(settings.samples) {
                            if (stability) delay(1000)
                            withTimeout(timeout.toLong()) { GroupProbe.measure(group, ip, config) }.latencyMs
                        }
                    }
                } finally {
                    withContext(NonCancellable) { relay.awaitClosed(); down?.awaitClosed() }
                }
            }
            clean = true
        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* One failed IP cannot cancel the batch. */ }
        CfResult(ip, series.times, series.attempts, if (clean && series.times.size == settings.samples) null else stage)
    }
}
