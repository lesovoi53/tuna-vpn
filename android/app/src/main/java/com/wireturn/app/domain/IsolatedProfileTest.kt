package com.wireturn.app.domain

import android.content.Context
import com.wireturn.app.CaBundleUtil
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.*
import com.wireturn.app.kernel.KernelCommandContext
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.kernel.signalAndroidProcess
import com.wireturn.app.kernel.terminateCoreProcess
import com.wireturn.app.vk.VkRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

/** A disposable test instance, separate from CoreService and all active-profile preferences.
 * Native children inherit the app UID, which the existing VPN excludes from its own TUN.
 * The listener always requires unique credentials, including when the user's proxy allows no auth.
 */
class IsolatedProfileTest(private val context: Context) {
    suspend fun <T> withTunnel(profile: Profile, block: suspend (ClientConfig) -> T): T =
        com.wireturn.app.kernel.OperationWakeLock.run(context, "profile-test") { withTunnelAwake(profile, block) }

    private suspend fun <T> withTunnelAwake(profile: Profile, block: suspend (ClientConfig) -> T): T = withContext(Dispatchers.IO) {
        val session = CoreServiceState.session.value
        if (ProbeEligibility.requiresActive(profile)) {
            check(session != null && session.profileId == profile.id &&
                CoreServiceState.status.value == CoreStatus.Connected) {
                "Сначала подключите этот сервер для тестирования"
            }
            return@withContext withActiveProbeSession({
                CoreServiceState.session.value === session && CoreServiceState.status.value == CoreStatus.Connected
            }) { block(session.clientConfig) }
        }
        WgProbeGuard.run(profile.kernelConfig, session?.clientConfig, CoreServiceState.status.value == CoreStatus.Connected,
            isolated = { withIsolatedTunnel(profile, block) },
            reuse = { config ->
                val result = block(config)
                check(CoreServiceState.session.value === session && CoreServiceState.status.value == CoreStatus.Connected) {
                    "Подключение изменилось во время теста"
                }
                result
            })
    }

    private suspend fun <T> withIsolatedTunnel(profile: Profile, block: suspend (ClientConfig) -> T): T = withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "profile-test-").toFile()
        val vk = VkRuntime(context)
        var child: Process? = null
        var dnsBridge: com.wireturn.app.kernel.SystemDnsBridge? = null
        var reader: Job? = null
        var killer: Job? = null
        val reservations = mutableListOf<ServerSocket>()
        try {
            fun reserve(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { reservations += it }.localPort
            val socksPort = reserve()
            val listenPort = reserve()
            val preferences = AppPreferences(context)
            val base = preferences.clientConfigFlow.first()
            val config = com.wireturn.app.data.kernel.VpnFragmentation.applyDefault(base.copy(kernelConfig = profile.kernelConfig, uri = "", turnableUrl = "", olcrtcUrl = "", webdavUrl = "", freeturnUrl = "",
                socksAddr = "127.0.0.1:$socksPort", listenAddr = "127.0.0.1:$listenPort", isSocksAuthEnabled = true,
                socksUser = UUID.randomUUID().toString(), socksPass = UUID.randomUUID().toString()).fillDefaults(), preferences.defaultFragmentationFlow.first())
            val prepared = withTimeout(60_000L) { vk.prepare(config) }
            val network = AppPreferences(context).networkSettingsForProfile(profile)
            val bootstrap = if ((prepared.kernelConfig as? KernelConfig.Vpn)?.config?.isRawXray == true) network.dns.bootstrap
                else com.wireturn.app.kernel.EffectiveDnsBootstrap.resolve(network)
            dnsBridge = com.wireturn.app.kernel.SystemDnsBridge(context)
            val commandContext = object : KernelCommandContext {
                override val bootstrapDns = bootstrap
                override val filesDir = directory
                override val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
                override val xrayAssetsDir = GeoAssetsManager.assetsDir(context)
            }
            val command = KernelRegistry.get(prepared.kernelVariant).buildCommand(commandContext, prepared)
            val ready = CompletableDeferred<Unit>()
            val ca = if (prepared.useCustomCerts) CaBundleUtil.ensure(context) else null
            val process = withContext(Dispatchers.IO) {
                reservations.forEach { it.close() }
                ProcessBuilder(command).directory(directory).redirectErrorStream(true).apply {
                    dnsBridge.applyTo(environment())
                    environment().remove("TUNA_BOOTSTRAP_DNS")
                    if (bootstrap.isNotBlank()) environment()["TUNA_BOOTSTRAP_DNS"] = BootstrapDns.parse(bootstrap)!!.xrayAddress()
                    environment()["LD_LIBRARY_PATH"] = commandContext.nativeLibraryDir
                    environment()["PATH"] = "${commandContext.nativeLibraryDir}:${environment()["PATH"].orEmpty()}"
                    if ((prepared.kernelConfig as? KernelConfig.Vpn)?.config?.usesXray == true) {
                        environment().remove("TUNA_BOOTSTRAP_DNS")
                        if (bootstrap.isNotBlank()) environment()["TUNA_BOOTSTRAP_DNS"] = BootstrapDns.parse(bootstrap)!!.xrayAddress()
                        environment()["GODEBUG"] = if (bootstrap.isBlank()) "netdns=cgo" else "netdns=go"
                    }
                    else if (prepared.kernelVariant == KernelVariant.WEBDAV) environment()["GODEBUG"] = "netdns=cgo"
                    else if (prepared.goDnsGo) environment()["GODEBUG"] = "netdns=go"
                    if (ca != null) environment()["SSL_CERT_FILE"] = ca
                }.let { com.wireturn.app.kernel.MemoryConfigs.start(it) }.also { child = it }
            }
            killer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { signalAndroidProcess(process, 15) }
            }
            reader = launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            ensureActive()
                            val lower = line.lowercase()
                            if (line.startsWith("VK_AUTH_REQUIRED|") && prepared.kernelVariant == KernelVariant.QWDTT) {
                                val hash = line.substringAfter('|').trim()
                                val q = (prepared.kernelConfig as KernelConfig.Qwdtt).config
                                if (q.accountAuth && hash in q.hashList) {
                                    val credentials = com.wdtt.client.VkAuthWebViewManager.authenticate(context, hash).getOrThrow()
                                    synchronized(process.outputStream) {
                                        process.outputStream.write(com.wdtt.client.VkAuthWebViewManager.encodeTurnCredsPayload(hash, credentials).toByteArray())
                                        process.outputStream.flush()
                                    }
                                }
                            }
                            if (isIsolatedProfileReady(prepared.kernelVariant, line)) ready.complete(Unit)
                            if (isIsolatedProfileFatal(prepared.kernelVariant, line)) {
                                ready.completeExceptionally(IllegalStateException("Тестовое ядро не подключилось"))
                            }
                        }
                    }
                    ready.completeExceptionally(IllegalStateException("Тестовое ядро завершилось"))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { ready.completeExceptionally(IllegalStateException("Ошибка запуска тестового ядра")) }
            }
            withTimeout(45_000L) { ready.await() }
            if (com.wireturn.app.kernel.SnellDestinationResolver.required(prepared.kernelConfig)) {
                IsolatedSnellResolver.withResolver(context, directory, prepared, network, block)
            } else block(prepared)
        } finally {
            dnsBridge?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                reservations.forEach { runCatching { it.close() } }
                child?.let { process ->
                    // Do not close a pipe while its reader is blocked: signal and reap first.
                    terminateCoreProcess(process, { signalAndroidProcess(it, 15) },
                        forceTerminate = { signalAndroidProcess(it, 9) })
                }
                reader?.cancelAndJoin()
                killer?.cancelAndJoin()
                com.wireturn.app.kernel.MemoryConfigs.removeDirectory(directory)
                directory.deleteRecursively()
            }
        }
    }
}
