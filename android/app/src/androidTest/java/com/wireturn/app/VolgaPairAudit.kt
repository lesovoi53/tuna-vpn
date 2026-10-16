package com.wireturn.app

import android.content.Context
import com.wireturn.app.kernel.signalAndroidProcess
import com.wireturn.app.kernel.terminateCoreProcess
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Optional native diagnostic exists only in a locally prepared debug APK. */
object VolgaPairAudit {
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libvolgaaudit.so")
        check(binary.isFile) { "Local diagnostic binary missing" }
        val fixture = File(context.filesDir, "qa-rc31-volga-docs.json")
        check(fixture.isFile)
        val log = File(context.filesDir, "qa-rc31-volga-pair.private.log")
        val process = ProcessBuilder(binary.absolutePath, "-test.run=TestTunaLiveVolgaPair", "-test.v", "-test.timeout=180s")
            .redirectErrorStream(true).redirectOutput(log).apply {
                environment()["TUNA_VOLGA_PAIR_FIXTURE"] = fixture.absolutePath
                environment()["TUNA_VOLGA_COOKIE_FILE"] = File(context.noBackupFilesDir, "volga-session/cookies.txt").absolutePath
            }.start()
        try {
            check(process.waitFor(190, TimeUnit.SECONDS)) { "Pair test deadline" }
            "native-exit=${process.exitValue()} log-bytes=${log.length()}"
        } finally {
            withContext(NonCancellable) {
                terminateCoreProcess(process, { signalAndroidProcess(it, 15) }, forceTerminate = { signalAndroidProcess(it, 9) })
            }
        }
    }
}
