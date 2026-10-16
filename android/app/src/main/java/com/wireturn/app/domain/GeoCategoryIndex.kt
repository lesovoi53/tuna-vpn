package com.wireturn.app.domain

import java.io.File

/** Reads only country_code from Xray GeoSiteList/GeoIPList protobufs; skips lists. */
object GeoCategoryIndex {
    fun categoryOf(expression: String): String {
        val base = expression.substringBefore('@')
        val prefix = base.substringBefore(':') + ":"
        val name = base.substringAfter(':')
        return prefix + (if (prefix == "geoip:") name.removePrefix("!") else name).lowercase(java.util.Locale.ROOT)
    }
    fun read(file: File, prefix: String): List<String> {
        if (!file.isFile) return emptyList()
        require(file.length() <= 80_000_000) { "База категорий слишком велика" }
        val bytes = file.readBytes()
        fun fields(start: Int, end: Int, onString: (Int, Int, Int) -> Unit) {
            var pos = start
            fun number(): Int {
                var result = 0; var shift = 0
                while (pos < end && shift < 35) {
                    val b = bytes[pos++].toInt() and 255
                    result = result or ((b and 127) shl shift)
                    if (b and 128 == 0) return result
                    shift += 7
                }
                error("Повреждённая база категорий")
            }
            while (pos < end) {
                val tag = number()
                when (tag and 7) {
                    0 -> number()
                    1 -> pos += 8
                    5 -> pos += 4
                    2 -> { val size = number(); require(size >= 0 && size <= end - pos); onString(tag ushr 3, pos, pos + size); pos += size }
                    else -> error("Неверный формат базы категорий")
                }
                require(pos <= end)
            }
        }
        val names = mutableListOf<String>()
        fields(0, bytes.size) { field, start, end ->
            if (field == 1) fields(start, end) { nested, a, b ->
                if (nested == 1 && b - a in 1..128) names += prefix + String(bytes, a, b - a, Charsets.UTF_8).lowercase()
            }
        }
        return names.distinct().sorted()
    }
}
