package com.wireturn.app.kernel

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns all children from creation through termination, including cancellation during start. */
class RestartingProcessGroup(
    private val commands: List<Command>,
    private val onLine: (Int, String) -> Unit = { _, _ -> },
    private val onExit: (Int) -> Unit = {},
    private val onFailure: (Int) -> Unit = {},
    private val permanentFailure: (Int, String) -> Boolean = { _, _ -> false },
    private val onPermanentFailure: (Int) -> Unit = {},
    private val onAttempt: (Int, Int) -> Unit = { _, _ -> },
    private val onTermination: (Int, Int, String, Int?, Throwable?) -> Unit = { _, _, _, _, _ -> },
    private val restartDelayMs: Long = 1_000,
    private val terminateProcess: (Process) -> Unit = { it.destroy() },
    private val killProcess: (Process) -> Unit = { it.destroyForcibly() },
    private val startProcess: (Command) -> Process = { command ->
        ProcessBuilder(command.args).directory(command.directory).redirectErrorStream(true).apply {
            environment().putAll(command.environment)
        }.start()
    }
) {
    data class Command(val id: Int, val args: List<String>, val directory: File,
        val environment: Map<String, String> = emptyMap())

    private val stopped = AtomicBoolean(false)
    private val finished = CompletableDeferred<Unit>()
    private val lock = Any()
    private val stopMutex = Mutex()
    private val processes = mutableMapOf<Int, Process>()

    suspend fun run(): Unit = coroutineScope {
        require(commands.map { it.id }.distinct().size == commands.size)
        val jobs = commands.map { command -> launch(Dispatchers.IO) {
            var failures = 0
            var attempt = 0
            while (isActive && !stopped.get()) {
                var process: Process? = null
                var permanent = false
                var failure: Throwable? = null
                val started = System.nanoTime()
                onAttempt(command.id, ++attempt)
                try {
                    // stop() cannot pass between start() and registration of the child.
                    process = synchronized(lock) {
                        if (stopped.get()) null else startProcess(command).also { processes[command.id] = it }
                    } ?: break
                    process.inputStream.bufferedReader().use { reader ->
                        while (isActive && !stopped.get()) {
                            val line = reader.readLine() ?: break
                            if (!stopped.get()) onLine(command.id, line)
                            if (permanentFailure(command.id, line)) {
                                permanent = true
                                onPermanentFailure(command.id)
                                break
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    failure = cancelled
                    // Cancellation is not a failed native startup or an automatic restart.
                    throw cancelled
                } catch (error: Exception) {
                    failure = error
                    if (!stopped.get()) onFailure(command.id)
                } finally {
                    val cancelled = failure is CancellationException || !isActive
                    // stdout may reach EOF a few milliseconds before the OS publishes exit status.
                    if (!cancelled && !stopped.get() && failure == null && !permanent) {
                        process?.waitFor(250, TimeUnit.MILLISECONDS)
                    }
                    val exitCode = process?.let { runCatching { it.exitValue() }.getOrNull() }
                    val reason = when {
                        stopped.get() -> "stopped"
                        cancelled -> "cancelled"
                        permanent -> "incompatible_document"
                        failure != null && process == null -> "start_failed"
                        failure != null -> "read_failed"
                        exitCode != null -> "exited"
                        else -> "stdout_closed"
                    }
                    onTermination(command.id, attempt, reason, exitCode, failure)
                    process?.let { child ->
                        terminate(child)
                        synchronized(lock) { if (processes[command.id] === child) processes.remove(command.id) }
                    }
                    if (!stopped.get() && !permanent && !cancelled) onExit(command.id)
                }
                if (stopped.get() || permanent) break
                failures = if (System.nanoTime() - started > 30_000_000_000L) 0 else (failures + 1).coerceAtMost(5)
                delay((restartDelayMs * (1L shl failures)).coerceAtMost(30_000))
            }
        } }
        try { finished.await() } finally {
            withContext(NonCancellable) {
                stop()
                jobs.forEach { it.cancelAndJoin() }
            }
        }
    }

    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        stopMutex.withLock {
            val children = synchronized(lock) {
                stopped.set(true)
                finished.complete(Unit)
                processes.values.toList()
            }
            coroutineScope { children.map { launch { terminate(it) } }.joinAll() }
        }
    }

    private suspend fun terminate(child: Process) {
        terminateCoreProcess(child, terminateProcess, graceMillis = 3_000, forceMillis = 2_000,
            forceTerminate = killProcess)
    }
}
