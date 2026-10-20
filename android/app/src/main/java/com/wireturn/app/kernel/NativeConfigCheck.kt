package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.data.kernel.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.TimeUnit

/** Fixed presentation text only; never attach native stderr or generated configuration. */
internal class NativeConfigCheckFailure(val kind: Kind) : IllegalArgumentException(when (kind) {
    Kind.TIMEOUT -> "Проверка ядром не завершилась за 15 секунд"
    Kind.REJECTED -> "Ядро отклонило конфигурацию: неподдерживаемые или некорректные параметры. Это не ошибка доступности сервера."
}) {
    enum class Kind { TIMEOUT, REJECTED }
}

object NativeConfigCheck {
    private val permits = Semaphore(2)
    private val accepted = linkedSetOf<String>()
    /** Private files and bounded execution; never logs generated config/credentials. */
    suspend fun check(context: Context, profile: VpnConfig, preview: Boolean = false): String = permits.withPermit { withContext(Dispatchers.IO) {
        profile.validate()
        if (profile.protocol == "mieru") return@withContext "Mieru: параметры ссылки проверены; отдельное ядро 3.38.1"
        val config = if (profile.usesXray) XrayVpnConfig.generate(profile, "127.0.0.1:21089", "validation", "validation", false)
            else SingBoxConfig.generate(profile, "127.0.0.1:21089", "validation", "validation")
        if (profile.isRawXray) {
            val assets = com.wireturn.app.domain.GeoAssetsManager.assetsDir(context)
            RawXrayConfig.validateAssets(profile, assets)
            config.addProperty("assets_path", assets.absolutePath)
        }
        val library = context.applicationInfo.nativeLibraryDir
        val binary = File(library, if (profile.usesXray) "libxray.so" else "libsingbox.so")
        val cacheKey = java.security.MessageDigest.getInstance("SHA-256").digest(
            (binary.absolutePath + ":" + binary.length() + ":" + binary.lastModified() + ":" + config).toByteArray()
        ).joinToString("") { "%02x".format(it) }
        val success = "Конфигурация принята ядром. Доступность сервера ещё не проверена."
        // Raw JSON can reference mutable asset files: never cache its validation.
        val cacheable = !preview && !profile.isRawXray
        currentCoroutineContext().ensureActive()
        if (cacheable && synchronized(accepted) { cacheKey in accepted }) return@withContext success
        val input = File(context.cacheDir, "core-check-${java.util.UUID.randomUUID()}.json")
        var output: java.util.concurrent.FutureTask<String>? = null
        var process: Process? = null
        try {
            MemoryConfigs.write(input, config.toString())
            val command = if (profile.usesXray) listOf("$library/libxray.so", "-config", input.absolutePath, if (preview) "-dump-config" else "-check-config")
                else listOf("$library/libsingbox.so", "check", "-c", input.absolutePath)
            process = ProcessBuilder(command).redirectErrorStream(true).apply {
                if (!preview) redirectOutput(File("/dev/null"))
                environment()["GODEBUG"] = "netdns=cgo"
                environment().remove("TUNA_ROUTING_FILE")
            }.let { MemoryConfigs.start(it) }
            if (preview) {
                val stream = process.inputStream
                output = java.util.concurrent.FutureTask {
                    val captured = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    stream.use {
                        while (true) {
                            val count = it.read(chunk)
                            if (count < 0) break
                            val keep = minOf(count, 262144 - captured.size())
                            if (keep > 0) captured.write(chunk, 0, keep)
                        }
                    }
                    captured.toString("UTF-8")
                }.also { Thread(it, "native-preview").apply { isDaemon = true; start() } }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) throw NativeConfigCheckFailure(NativeConfigCheckFailure.Kind.TIMEOUT)
            }
            currentCoroutineContext().ensureActive()
            // Core stderr can include encryption keys and URLs. Never expose it in UI/logs.
            if (process.exitValue() != 0) throw NativeConfigCheckFailure(NativeConfigCheckFailure.Kind.REJECTED)
            if (cacheable) synchronized(accepted) {
                if (accepted.size >= 128) accepted.remove(accepted.first())
                accepted.add(cacheKey)
            }
            val text = output?.get(1, TimeUnit.SECONDS).orEmpty()
            if (!preview) success
            else if (!profile.usesXray) config.toString()
            else text.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: error("Ядро не вернуло конфигурацию")
        } finally {
            try {
                process?.let { terminateCoreProcess(it, { child -> signalAndroidProcess(child, 15) },
                    graceMillis = 1_000, forceTerminate = { child -> signalAndroidProcess(child, 9) }) }
            } finally {
                MemoryConfigs.remove(input)
                output?.cancel(true)
            }
        }
    } }
}
