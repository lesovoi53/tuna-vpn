package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.data.SharedNetworkSettings
import com.wireturn.app.data.XraySettings
import com.wireturn.app.domain.GeoAssetsManager
import com.wireturn.app.domain.SubscriptionRoutingAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Compile routing with the packaged core before publishing settings or asset generations.
 * -check-config constructs and closes Xray without starting listeners or dialling an upstream.
 */
object RoutingConfigCheck {
    suspend fun settings(context: Context, settings: XraySettings) {
        val compiled = XrayNetworkCompiler.compile(SharedNetworkSettings.fromLegacy(settings), hevEnabled = true)
        val assets = if (compiled.subscriptionSource.isNotBlank())
            SubscriptionRoutingAssets.directory(context, compiled.subscriptionSource) else GeoAssetsManager.assetsDir(context)
        policy(context, compiled.policyJson, assets, compiled.dnsServers)
    }

    suspend fun policy(context: Context, json: String, assets: File, dns: String = XraySettings.DEFAULT_DNS) = withContext(Dispatchers.IO) {
        val input = File.createTempFile("route-check-", ".json", context.cacheDir)
        val output = File.createTempFile("route-check-", ".log", context.cacheDir)
        var process: Process? = null
        try {
            input.writeText(json)
            currentCoroutineContext().ensureActive()
            process = ProcessBuilder(
                "${context.applicationInfo.nativeLibraryDir}/libxray.so", "-check-config",
                "-local-socks5", "127.0.0.1:1", "-listen", "127.0.0.1:1",
                "-dns", dns, "-assets-path", assets.absolutePath,
            ).redirectErrorStream(true).redirectOutput(output).apply {
                environment()["TUNA_ROUTING_FILE"] = input.absolutePath
                environment()["GODEBUG"] = "netdns=cgo"
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                require(System.nanoTime() < deadline) { "Проверка правил не завершилась за 15 секунд. Настройки сохранены без изменений." }
            }
            currentCoroutineContext().ensureActive()
            require(process.exitValue() == 0) {
                "Xray отклонил правила или DNS. Проверьте выражения и необходимые базы. Предыдущие настройки сохранены."
            }
        } finally {
            try {
                process?.let { terminateCoreProcess(it, { child -> signalAndroidProcess(child, 15) },
                    graceMillis = 1_000, forceTerminate = { child -> signalAndroidProcess(child, 9) }) }
            } finally { input.delete(); output.delete() }
        }
    }
}
