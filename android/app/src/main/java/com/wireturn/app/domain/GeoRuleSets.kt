package com.wireturn.app.domain

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.net.InetAddress

/** Convert only requested Xray protobuf categories. No downloads and no guessed categories. */
object GeoRuleSets {
    fun load(directory: File, matches: List<String>): Map<String, JsonObject> {
        val requested = matches.filter { it.startsWith("geosite:") || it.startsWith("geoip:") }.distinct()
        val result = linkedMapOf<String, JsonObject>()
        for (prefix in listOf("geosite:", "geoip:")) {
            val selected = requested.filter { it.startsWith(prefix) }
            if (selected.isEmpty()) continue
            val file = File(directory, if (prefix == "geosite:") "geosite.dat" else "geoip.dat")
            require(file.isFile && file.length() in 1..80_000_000) { "Недоступна база ${file.name}" }
            val data = file.readBytes()
            val categories = selected.groupBy { it.removePrefix(prefix).substringBefore('@').lowercase() }
            require(categories.keys.none { it.startsWith("!") }) { "Инверсия geoip пока требует Xray" }
            for (entry in Message(data).fields().filter { it.id == 1 && it.wire == 2 }) {
                val code = entry.message().fields().firstOrNull { it.id == 1 && it.wire == 2 }?.text()?.lowercase() ?: continue
                if (code !in categories) continue
                val fields = entry.message().fields().toList()
                for (match in categories[code].orEmpty()) {
                    require(match !in result) { "Повтор категории $code в базе" }
                    val rule = JsonObject()
                    fun add(key: String, value: String) {
                        val values = rule.getAsJsonArray(key) ?: JsonArray().also { rule.add(key, it) }
                        require(values.size() < 1_000_000) { "Слишком большая категория $code" }
                        values.add(value)
                    }
                    if (prefix == "geosite:") {
                        val attributes = match.split('@').drop(1).map(String::lowercase)
                        for (domain in fields.filter { it.id == 2 && it.wire == 2 }) {
                            val parts = domain.message().fields().toList()
                            val keys = parts.filter { it.id == 3 && it.wire == 2 }.mapNotNull {
                                it.message().fields().firstOrNull { f -> f.id == 1 && f.wire == 2 }?.text()
                            }.toSet()
                            // Xray attribute matching checks key presence, not the protobuf value.
                            if (!attributes.all { it in keys }) continue
                            val type = parts.firstOrNull { it.id == 1 && it.wire == 0 }?.number ?: 0
                            val value = parts.firstOrNull { it.id == 2 && it.wire == 2 }?.text() ?: error("Домен без значения")
                            require(value.isNotBlank())
                            val key = when (type) {
                                0L -> "domain_keyword"; 1L -> "domain_regex"; 2L -> "domain_suffix"; 3L -> "domain"
                                else -> error("Неизвестный тип домена в базе")
                            }
                            add(key, value)
                        }
                    } else {
                        require('@' !in match) { "Атрибуты geoip не поддерживаются" }
                        require(fields.none { it.id == 3 && it.number != 0L }) { "Инверсия geoip в базе требует Xray" }
                        for (cidr in fields.filter { it.id == 2 && it.wire == 2 }) {
                            val parts = cidr.message().fields().toList()
                            val ip = parts.firstOrNull { it.id == 1 && it.wire == 2 }?.bytes() ?: error("CIDR без IP")
                            require(ip.size == 4 || ip.size == 16)
                            val mask = parts.firstOrNull { it.id == 2 && it.wire == 0 }?.number ?: 0L
                            require(mask in 0L..(ip.size * 8).toLong())
                            add("ip_cidr", "${InetAddress.getByAddress(ip).hostAddress}/$mask")
                        }
                    }
                    // An empty sing-box rule would match everything, not nothing.
                    require(rule.size() > 0) { "Категория $match пуста после фильтрации" }
                    result[match] = rule
                }
            }
            require(selected.all { it in result }) { "В базе нет категорий: ${selected.filterNot { it in result }.joinToString()}" }
        }
        return result
    }

    private class Message(val data: ByteArray, val start: Int = 0, val end: Int = data.size) {
        fun fields(): Sequence<Field> = sequence {
            var pos = start
            fun varint(): Long {
                var value = 0L
                for (shift in 0..63 step 7) {
                    require(pos < end) { "Обрезанный protobuf" }
                    val b = data[pos++].toInt() and 255
                    require(shift < 63 || b <= 1) { "Переполнение protobuf" }
                    value = value or ((b and 127).toLong() shl shift)
                    if (b and 128 == 0) return value
                }
                error("Неверный varint")
            }
            while (pos < end) {
                val tag = varint()
                require(tag in 8..0xffffffffL)
                val id = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                when (wire) {
                    0 -> yield(Field(id, wire, varint(), data, pos, pos))
                    1, 5 -> { val size = if (wire == 1) 8 else 4; require(size <= end - pos); pos += size }
                    2 -> {
                        val length = varint()
                        require(length in 0..(end - pos).toLong()) { "Неверная длина protobuf" }
                        val next = pos + length.toInt()
                        yield(Field(id, wire, 0, data, pos, next)); pos = next
                    }
                    else -> error("Неизвестный тип protobuf")
                }
            }
        }
    }
    private data class Field(val id: Int, val wire: Int, val number: Long, val data: ByteArray, val start: Int, val end: Int) {
        fun message() = Message(data, start, end)
        fun bytes() = data.copyOfRange(start, end)
        fun text() = String(data, start, end - start, Charsets.UTF_8)
    }
}
