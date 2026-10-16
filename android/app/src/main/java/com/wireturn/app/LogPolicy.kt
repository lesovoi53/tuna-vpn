package com.wireturn.app

enum class LogLevel(val label: String) {
    ALL("Все события"), WARNING("Предупреждения и ошибки"), ERROR("Ошибки"), FATAL("Только критические");
    fun accepts(message: String): Boolean = this == ALL || severity(message) >= ordinal

    companion object {
        fun severity(message: String): Int {
            val text = message.lowercase()
            // Explicit core levels take precedence over counter/URL/error words in the body.
            val level = Regex("\\[(trace|debug|info|warning|warn|error|fatal|panic)\\]", RegexOption.IGNORE_CASE)
                .find(message)?.groupValues?.get(1)?.lowercase()
            if (level != null) return when (level) {
                "fatal", "panic" -> 3
                "error" -> 2
                "warning", "warn" -> 1
                else -> 0
            }
            val content = text.replace(Regex("\\b(?:errors?|failed)\\s*[:=]\\s*0\\b"), "")
            if (Regex("\\bfatal(?!\\s*=\\s*false)").containsMatchIn(content) ||
                listOf("panic", "критическ", "фатал").any(content::contains)) return 3
            if (listOf("error", "ошибк", "failed", "exception", "отклон", "не удалось").any(content::contains)) return 2
            if (listOf("warn", "timeout", "таймаут", "reconnect", "недоступ", "unavailable", "refused", "переподключ").any(content::contains)) return 1
            return 0
        }
    }
}

/** The byte/character cap also limits unusually long native errors. */
object LogRetention {
    val capacities = listOf(200, 500, 1000, 2000)
    fun trim(entries: List<AppLogsState.LogEntry>, capacity: Int): List<AppLogsState.LogEntry> {
        val tail = entries.takeLast(capacity.coerceIn(200, 2000))
        var characters = 0
        return tail.asReversed().takeWhile { characters += it.message.length; characters <= 1_000_000 }.asReversed()
    }
}
