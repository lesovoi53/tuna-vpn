package com.wireturn.app.kernel

/** Process control must inspect native severity, never words in a destination or error body. */
internal object VpnCoreLog {
    private val nativeLevel = Regex(
        "^(?:(?:[+-]\\d{4}\\s+)?\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+" +
            "\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+)?" +
            "(?:FATAL(?:\\[[^]\\r\\n]*])?(?:\\s|:|$)|\\[(?:FATAL|PANIC)](?:\\s|$))",
        RegexOption.IGNORE_CASE
    )
    private val goRuntime = Regex("^(?:panic:|fatal error:)", RegexOption.IGNORE_CASE)
    private val glogFatal = Regex("^F\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+")

    fun isFatal(line: String): Boolean {
        val text = line.trimStart()
        return nativeLevel.containsMatchIn(text) || goRuntime.containsMatchIn(text) ||
            glogFatal.containsMatchIn(text)
    }
}
