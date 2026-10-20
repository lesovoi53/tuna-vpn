package com.wireturn.app.domain

import android.content.Context
import com.wireturn.app.CaBundleUtil
import com.wireturn.app.data.BootstrapDns
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.kernel.SnellDestinationResolver
import com.wireturn.app.kernel.signalAndroidProcess
import com.wireturn.app.kernel.terminateCoreProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** Uses the same Xray DNS and SOCKS destination resolution as the live Snell VPN. */
internal object IsolatedSnellResolver {
    /** Probe the already running core with DNS parity, without routing through DIRECT rules. */
    suspend fun <T> withActiveKernel(context: Context, config: ClientConfig,
        block: suspend (ClientConfig) -> T): T =
        com.wireturn.app.kernel.OperationWakeLock.run(context, "health-test") { withActiveKernelAwake(context, config, block) }

    private suspend fun <T> withActiveKernelAwake(context: Context, config: ClientConfig,
        block: suspend (ClientConfig) -> T): T {
        if (!SnellDestinationResolver.required(config.kernelConfig)) return block(config)
        return withContext(Dispatchers.IO) {
            val network = AppPreferences(context).sharedNetworkSettingsFlow.first()
            val directory = File(context.cacheDir, "snell-health-${java.util.UUID.randomUUID()}")
            check(directory.mkdirs()) { "Cannot prepare Snell health probe" }
            try { withResolver(context, directory, config, network, block) }
            finally { com.wireturn.app.kernel.MemoryConfigs.removeDirectory(directory); directory.deleteRecursively() }
        }
    }

    suspend fun <T> withResolver(context: Context, directory: File, config: ClientConfig,
        network: SharedNetworkSettings, block: suspend (ClientConfig) -> T): T = coroutineScope {
        val policy = File(directory, "snell-dns-policy.json").apply {
            com.wireturn.app.kernel.MemoryConfigs.write(this, SnellDestinationResolver.probePolicy(network))
        }
        val reservation = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val address = "127.0.0.1:${reservation.localPort}"
        val localConfig = config.copy(socksAddr = address, isSocksAuthEnabled = true,
            socksUser = java.util.UUID.randomUUID().toString(), socksPass = java.util.UUID.randomUUID().toString())
        var child: Process? = null
        var dnsBridge: com.wireturn.app.kernel.SystemDnsBridge? = null
        var reader: Job? = null
        var killer: Job? = null
        try {
            dnsBridge = com.wireturn.app.kernel.SystemDnsBridge(context)
            val ca = if (config.useCustomCerts) CaBundleUtil.ensure(context) else null
            val builder = ProcessBuilder(listOf("${context.applicationInfo.nativeLibraryDir}/libxray.so",
                "-listen", address, "-proxy-user", localConfig.socksUser, "-proxy-pass", localConfig.socksPass,
                "-local-socks5", SnellDestinationResolver.upstream(config),
                "-dns", network.dns.servers)).directory(directory).redirectErrorStream(true)
            dnsBridge.applyTo(builder.environment())
            builder.environment().apply {
                put(SnellDestinationResolver.ENVIRONMENT_KEY, "1")
                put("TUNA_ROUTING_FILE", policy.absolutePath)
                remove("TUNA_BOOTSTRAP_DNS")
                BootstrapDns.parse(com.wireturn.app.kernel.EffectiveDnsBootstrap.resolve(network))?.let { put("TUNA_BOOTSTRAP_DNS", it.xrayAddress()) }
                if (ca != null) put("SSL_CERT_FILE", ca)
            }
            ensureActive()
            reservation.close()
            val arguments = com.wireturn.app.kernel.MemoryConfigs.write(File(directory, "snell-args.json"),
                com.google.gson.Gson().toJson(builder.command().drop(1)))
            builder.command(builder.command().first(), "-args-config", arguments.absolutePath)
            val process = com.wireturn.app.kernel.MemoryConfigs.start(builder).also { child = it }
            killer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { signalAndroidProcess(process, 15) }
            }
            val ready = CompletableDeferred<Unit>()
            reader = launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            ensureActive()
                            if (line.contains("Xray started ->")) ready.complete(Unit)
                        }
                    }
                    ready.completeExceptionally(IllegalStateException("Snell DNS resolver exited"))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { ready.completeExceptionally(IllegalStateException("Snell DNS resolver failed")) }
            }
            withTimeout(15_000L) { ready.await() }
            block(localConfig)
        } finally {
            dnsBridge?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                reservation.close()
                child?.let { process -> terminateCoreProcess(process, { signalAndroidProcess(it, 15) },
                    forceTerminate = { signalAndroidProcess(it, 9) }) }
                reader?.cancelAndJoin()
                killer?.cancelAndJoin()
            }
        }
    }
}
