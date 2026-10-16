package com.wireturn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AppLogsState {
    
    data class LogEntry(val id: Long, val message: String, val repeats: Int = 1) {
        val display: String get() = if (repeats > 1) "$message  ×$repeats" else message
    }

    @Volatile private var capacity = 500
    private val _detailed = MutableStateFlow(false)
    val detailed = _detailed.asStateFlow()
    private val _filter = MutableStateFlow(LogLevel.ALL)
    val filter = _filter.asStateFlow()
    private val _capacity = MutableStateFlow(500)
    val size = _capacity.asStateFlow()
    fun configure(context: android.content.Context) {
        PersistentDiagnosticLog.initialize(context)
        NetworkDiagnosticObserver.initialize(context)
        val prefs = context.getSharedPreferences("log_settings", 0)
        capacity = prefs.getInt("capacity", 500).takeIf { it in LogRetention.capacities } ?: 500
        _capacity.value = capacity
        _detailed.value = prefs.getBoolean("detailed", false)
        _filter.value = runCatching { LogLevel.valueOf(prefs.getString("filter", "ALL")!!) }.getOrDefault(LogLevel.ALL)
        _logs.update { LogRetention.trim(it, capacity) }
    }
    fun save(context: android.content.Context, size: Int = capacity, level: LogLevel = _filter.value, detail: Boolean = _detailed.value) {
        context.getSharedPreferences("log_settings", 0).edit().putInt("capacity", size)
            .putString("filter", level.name).putBoolean("detailed", detail).apply()
        configure(context)
    }
    fun addProtocolLog(protocol: String, message: String) {
        val line = if (message.startsWith("[$protocol]")) message else "[$protocol] $message"
        val lower = line.lowercase()
        val verbose = line.contains("detail ") || (protocol == "qWDTT" &&
            listOf("channelbind", "allocation", "refresh", "[turn]", "[dns]").any(lower::contains))
        if (!_detailed.value && LogLevel.severity(line) == 0 && verbose) {
            // Disk diagnostics must not depend on the screen's verbosity/filter.
            PersistentDiagnosticLog.append(stripAnsi(line))
            return
        }
        addLog(line)
    }
    private val nextId = java.util.concurrent.atomic.AtomicLong()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun addLog(msg: String) {
        val cleanMsg = DiagnosticRedactor.clean(stripAnsi(msg)).take(8192)
        PersistentDiagnosticLog.append(cleanMsg)
        android.util.Log.i("TunaVpnCore", cleanMsg)
        _logs.update { current ->
            val last = current.lastOrNull()
            val next = if (last?.message == cleanMsg) current.dropLast(1) + last.copy(repeats = (last.repeats + 1).coerceAtMost(999999))
                else current + LogEntry(nextId.getAndIncrement(), cleanMsg)
            LogRetention.trim(next, capacity)
        }
    }

    private val ansiPattern = Regex("\u001B\\[[;\\d]*[mK]")
    fun stripAnsi(msg: String): String {
        return if ('\u001B' in msg) msg.replace(ansiPattern, "") else msg
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
