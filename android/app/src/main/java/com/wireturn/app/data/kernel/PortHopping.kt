package com.wireturn.app.data.kernel

object PortHopping {
    fun ranges(value: String): List<String> {
        val entries = value.split(',').map { it.trim().replace(':', '-') }
        require(entries.size in 1..128) { "Слишком много диапазонов портов" }
        return entries.map { entry ->
            require(entry.matches(Regex("[0-9]+(-[0-9]+)?"))) { "Порты: 443,20000-20100" }
            val parts = entry.split('-').map { it.toIntOrNull() ?: 0 }
            require(parts.all { it in 1..65535 } && parts.first() <= parts.last()) { "Диапазон портов должен быть в пределах 1–65535" }
            parts.joinToString("-")
        }.distinct()
    }
    fun seconds(value: String): Double {
        require(value.matches(Regex("[0-9]+(?:\\.[0-9]+)?s?"))) { "Интервал задаётся в секундах" }
        return value.removeSuffix("s").toDouble().also { require(it.isFinite() && it > 0 && it <= 86400) { "Интервал: больше 0 и не более 86400 секунд" } }
    }
}
