package com.wireturn.app.domain

import android.content.Context
import com.google.gson.Gson
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.kernel.MasterDnsKernel
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MasterDnsResolverResult(val resolver: String, val domain: String, val valid: Boolean, val upload: Int, val download: Int, val latencyMs: Long)

object MasterDnsResolverCheck {
    suspend fun scan(context: Context, config: MasterDnsConfig, onProgress: (String) -> Unit,
                     onResult: (MasterDnsResolverResult) -> Unit) = withContext(Dispatchers.IO) {
        config.validate()
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "masterdns-scan-").toFile()
        var process: Process? = null
        var killer: Job? = null
        try {
            val file = MasterDnsKernel.writeConfig(dir, config, "127.0.0.1:18000", UUID.randomUUID().toString(), UUID.randomUUID().toString(), true)
            val proc = ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libmasterdns.so", "-config", file.absolutePath, "-scan")
                .directory(dir).redirectErrorStream(true).start()
            process = proc
            killer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { proc.destroy() } }
            var completed = false
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ensureActive()
                    if (line.startsWith("TUNA_RESOLVER ")) {
                        val result = Gson().fromJson(line.substringAfter(' '), MasterDnsResolverResult::class.java)
                        withContext(Dispatchers.Main) { onResult(result) }
                    } else if (line.contains("Accepted (") || line.contains("Rejected (")) {
                        val progress = Regex("(?:Accepted|Rejected) \\((\\d+/\\d+)\\)").find(line)?.groupValues?.get(1)
                        if (progress != null) withContext(Dispatchers.Main) { onProgress("Проверено $progress") }
                    } else if (line == "TUNA_SCAN_DONE") completed = true
                }
            }
            ensureActive()
            check(completed && proc.waitFor() == 0) { "Не найдены подходящие резолверы или истёк таймаут проверки" }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.destroy()
                if (process?.waitFor(3, TimeUnit.SECONDS) == false) process?.destroyForcibly()
                killer?.cancelAndJoin()
                dir.deleteRecursively()
            }
        }
    }
}
