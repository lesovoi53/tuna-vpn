package com.wireturn.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in passive recorder. All preference writes, log I/O and ZIP work run on one bounded worker. */
object PersistentDiagnosticLog {
    const val MAX_FILES = RotatingDiagnosticStore.MAX_FILES
    const val MAX_FILE_BYTES = RotatingDiagnosticStore.MAX_FILE_BYTES
    const val MAX_STORAGE_BYTES = RotatingDiagnosticStore.MAX_STORAGE_BYTES
    const val MIN_FREE_BYTES = 256L * 1024 * 1024
    private const val QUEUE_LIMIT = 512
    private const val PREFS = "log_settings"
    private const val ENABLED_KEY = "persistent_enabled"
    private val lock = Object()
    private val queue = ArrayDeque<Command>()
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private val mutableStatus = MutableStateFlow("Постоянный журнал выключен")
    val status: StateFlow<String> = mutableStatus.asStateFlow()
    private var initialized = false
    private var preferencesLoaded = false
    private var desiredEnabled = false
    private var explicitSetting = false
    private var dropped = 0L
    private var reportedDropped = 0L
    private var application: Context? = null
    // The following state belongs exclusively to the worker.
    private var recording = false
    private var store: RotatingDiagnosticStore? = null
    private var previous = ""
    private var repeats = 0L
    private var repeatAt = 0L
    private var lastRepeat: Record? = null
    @Volatile private var lastFailure: String? = null

    private sealed interface Command
    private data object Initialize : Command
    private data class SetEnabled(val value: Boolean) : Command
    private data class Record(val text: String, val wall: Long, val elapsed: Long) : Command
    private data class Export(val output: OutputStream, val result: CompletableDeferred<Unit>) : Command

    fun initialize(context: Context) {
        if (!isMainProcess(context)) return
        synchronized(lock) {
            if (initialized) return
            application = context.applicationContext
            initialized = true
            queue.addLast(Initialize)
            Thread(::runWriter, "tuna-diagnostic-writer").apply { isDaemon = true }.start()
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        synchronized(lock) {
            if (!initialized) return
            if (enqueueControl(SetEnabled(value))) {
                explicitSetting = true
                desiredEnabled = value
                mutableEnabled.value = value
            }
        }
    }

    /** Messages must already be redacted by their producer; worker redacts again before disk. */
    fun append(message: String) {
        synchronized(lock) {
            if (!initialized || (!desiredEnabled && (preferencesLoaded || explicitSetting))) return
            if (queue.size >= QUEUE_LIMIT - 8) {
                dropped++
                publishStatus()
                return
            }
            queue.addLast(Record(message.take(8192), System.currentTimeMillis(), SystemClock.elapsedRealtime()))
            lock.notifyAll()
        }
    }

    /** Snapshot is serialized with writes. The caller opens/closes output off the UI thread. */
    suspend fun exportTo(output: OutputStream) {
        val result = CompletableDeferred<Unit>()
        synchronized(lock) {
            check(initialized) { "Diagnostic recorder is not initialized in the main process" }
            enqueueControl(Export(output, result))
        }
        // Keep ownership of the stream until the queued export finishes, even if UI cancels.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { result.await() }
    }

    private fun enqueueControl(command: Command): Boolean {
        if (queue.size >= QUEUE_LIMIT) {
            val iterator = queue.iterator()
            var removed = false
            while (iterator.hasNext()) {
                if (iterator.next() is Record) { iterator.remove(); dropped++; removed = true; break }
            }
            if (!removed) {
                // UI controls are exceptional; fail explicitly rather than growing a second queue.
                val error = IllegalStateException("Diagnostic command queue is full")
                if (command is Export) command.result.completeExceptionally(error)
                else { lastFailure = "Очередь управления заполнена"; publishStatus() }
                return false
            }
        }
        queue.addLast(command)
        lock.notifyAll()
        return true
    }

    private fun runWriter() {
        while (true) {
            val command = synchronized(lock) {
                while (queue.isEmpty()) lock.wait()
                queue.removeFirst()
            }
            try {
                when (command) {
                    Initialize -> {
                        val saved = application!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getBoolean(ENABLED_KEY, false)
                        synchronized(lock) {
                            preferencesLoaded = true
                            if (!explicitSetting) { desiredEnabled = saved; mutableEnabled.value = saved }
                        }
                        if (saved) { recording = true; marker("recorder_start process_restart", durable = true) }
                    }
                    is SetEnabled -> {
                        var stopFailure: Exception? = null
                        if (command.value != recording) {
                            if (command.value) {
                                previous = ""
                                marker("recorder_start", durable = true)
                            } else {
                                try { flushRepeats(); flushDrops(); marker("recorder_stop", durable = true) }
                                catch (error: Exception) { stopFailure = error }
                                finally {
                                    try { store?.close() }
                                    catch (closing: Exception) {
                                        if (stopFailure == null) stopFailure = closing else stopFailure.addSuppressed(closing)
                                    }
                                }
                                // An I/O failure must never prevent the user from opting out.
                                repeats = 0
                            }
                        }
                        val persisted = application!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(ENABLED_KEY, command.value).commit()
                        if (!command.value) recording = false
                        check(persisted) { "Cannot persist recorder setting" }
                        recording = command.value
                        stopFailure?.let { throw it }
                    }
                    is Record -> if (recording) {
                        flushDrops()
                        val clean = DiagnosticRedactor.clean(command.text).replace('\r', ' ').replace('\n', ' ')
                        if (clean == previous) {
                            repeats++
                            lastRepeat = command
                            if (command.elapsed - repeatAt >= 5_000) flushRepeats()
                        } else {
                            flushRepeats()
                            disk().append(format(command, clean))
                            previous = clean
                            repeatAt = command.elapsed
                        }
                    }
                    is Export -> {
                        flushRepeats()
                        if (recording) flushDrops()
                        disk().exportTo(command.output, metadata())
                        command.result.complete(Unit)
                    }
                }
                lastFailure = null
                synchronized(lock) { publishStatus() }
            } catch (error: Exception) {
                // Do not feed failures back through AppLogsState, which would recursively enqueue.
                lastFailure = if (error is DiagnosticStorageLowSpaceException)
                    "Запись приостановлена: свободного места меньше резерва 256 МиБ"
                else "Ошибка журнала: ${error.javaClass.simpleName}"
                if (command is Export) command.result.completeExceptionally(error)
                if (command is SetEnabled || command === Initialize) synchronized(lock) {
                    if (command === Initialize) preferencesLoaded = true
                    if (queue.none { it is SetEnabled } && (command is SetEnabled || !explicitSetting)) {
                        desiredEnabled = recording
                        mutableEnabled.value = recording
                    }
                }
                synchronized(lock) { publishStatus() }
            }
        }
    }

    private fun disk(): RotatingDiagnosticStore = store ?: RotatingDiagnosticStore(
        File(application!!.noBackupFilesDir, "diagnostics"), minimumUsableBytes = MIN_FREE_BYTES
    ).also { store = it }

    private fun marker(message: String, durable: Boolean = false) {
        disk().append(format(Record("", System.currentTimeMillis(), SystemClock.elapsedRealtime()), "[recorder] $message"), durable)
    }

    private fun format(record: Record, message: String) =
        "${Instant.ofEpochMilli(record.wall)} elapsed_ms=${record.elapsed} $message"

    private fun flushRepeats() {
        if (repeats == 0L) return
        val last = lastRepeat!!
        disk().append(format(last, "[recorder] previous_line_repeated=$repeats"))
        repeats = 0
        repeatAt = last.elapsed
    }

    private fun flushDrops() {
        val total = synchronized(lock) { dropped }
        if (total > reportedDropped) {
            marker("queue_overload dropped=${total - reportedDropped} total_dropped=$total")
            reportedDropped = total
        }
    }

    private fun metadata(): String = """
        TUNA passive diagnostic log
        exported_utc=${Instant.now()}
        enabled=$recording
        max_files=$MAX_FILES
        max_file_bytes=$MAX_FILE_BYTES
        max_storage_bytes=$MAX_STORAGE_BYTES
        min_free_bytes=$MIN_FREE_BYTES
        queue_capacity=$QUEUE_LIMIT
        queue_dropped_this_process=${synchronized(lock) { dropped }}
        duplicate_window_ms=5000
        file_order=ascending sequence; elapsed_ms resets on device reboot
        retention=oldest files overwritten at 10 GB (10000000000 bytes); no duration guarantee
        redaction=DiagnosticRedactor; no configuration files included
    """.trimIndent() + "\n" + StorageDiagnostic.report(application!!.dataDir)

    // Called with lock held, including from producer threads; never performs I/O.
    private fun publishStatus() {
        mutableStatus.value = (lastFailure ?: if (desiredEnabled) "Запись включена · до 10 ГБ" else "Постоянный журнал выключен") +
            if (dropped > 0) " · пропущено: $dropped" else ""
    }

    private fun isMainProcess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 28) {
        Application.getProcessName() == context.packageName
    } else {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName == context.packageName
    }
}
