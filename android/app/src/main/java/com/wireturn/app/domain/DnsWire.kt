package com.wireturn.app.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object DnsWire {
    fun query(id: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeShort(id); writeShort(0x0100); writeShort(1); writeShort(0); writeShort(0); writeShort(0)
            for (label in listOf("example", "com")) { writeByte(label.length); writeBytes(label) }
            writeByte(0); writeShort(1); writeShort(1)
        }
    }.toByteArray()

    fun validate(query: ByteArray, reply: ByteArray) {
        fun u16(at: Int): Int { require(at + 1 < reply.size) { "Короткий DNS-ответ" }; return ((reply[at].toInt() and 255) shl 8) or (reply[at+1].toInt() and 255) }
        require(reply.size >= query.size && reply[0] == query[0] && reply[1] == query[1]) { "Ответ другого DNS-запроса" }
        val flags = u16(2)
        require(flags and 0x8000 != 0 && flags and 0x7800 == 0) { "Некорректный DNS-ответ" }
        require(flags and 15 == 0) { "DNS вернул код ошибки ${flags and 15}" }
        require(flags and 0x0200 == 0) { "Ответ усечён — попробуйте TCP" }
        require(u16(4) == 1 && query.copyOfRange(12, query.size).contentEquals(reply.copyOfRange(12, query.size))) { "DNS-ответ не соответствует вопросу" }
        var offset = query.size
        var address = false
        repeat(u16(6)) {
            offset = skipName(reply, offset)
            val type = u16(offset); val clazz = u16(offset + 2); val size = u16(offset + 8)
            offset += 10
            require(offset + size <= reply.size) { "Неполный DNS-ответ" }
            if (type == 1 && clazz == 1 && size == 4) address = true
            offset += size
        }
        require(address) { "DNS не вернул IPv4 для example.com" }
    }

    private fun skipName(packet: ByteArray, start: Int): Int {
        var position = start
        var end = -1
        var expandedSize = 1 // Include the final root label.
        val visited = HashSet<Int>()
        while (true) {
            require(position in packet.indices && visited.add(position)) { "Повреждённое DNS-имя" }
            val labelPosition = position
            val size = packet[position++].toInt() and 255
            if (size == 0) return if (end >= 0) end else position
            if (size and 0xc0 == 0xc0) {
                require(position < packet.size) { "Неполный DNS-указатель" }
                val target = ((size and 0x3f) shl 8) or (packet[position++].toInt() and 255)
                // Compression references an earlier name, outside the fixed DNS header.
                require(target in 12 until labelPosition) { "Некорректный DNS-указатель" }
                if (end < 0) end = position
                position = target
            } else {
                require(size <= 63 && position + size <= packet.size) { "Повреждённое DNS-имя" }
                expandedSize += size + 1
                require(expandedSize <= 255) { "Слишком длинное DNS-имя" }
                position += size
            }
        }
    }
}
