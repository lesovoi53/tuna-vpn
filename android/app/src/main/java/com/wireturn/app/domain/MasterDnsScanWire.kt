package com.wireturn.app.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Locale

/** DNS reachability is only a shortlist hint. Native tunnel probes decide compatibility. */
internal object MasterDnsScanWire {
    data class Question(val name: String, val type: Int, val clazz: Int)

    fun query(id: Int, domain: String, nonce: String): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeShort(id); out.writeShort(0x0100); out.writeShort(1)
            out.writeShort(0); out.writeShort(0); out.writeShort(0)
            val labels = if (domain.length + nonce.length + 1 <= 253) "$nonce.$domain" else domain
            labels.split('.').forEach { label ->
                require(label.length in 1..63)
                out.writeByte(label.length); out.writeBytes(label)
            }
            out.writeByte(0); out.writeShort(16); out.writeShort(1)
        }
    }.toByteArray()

    fun id(bytes: ByteArray): Int = u16(bytes, 0)
    fun setId(bytes: ByteArray, id: Int) { bytes[0] = (id ushr 8).toByte(); bytes[1] = id.toByte() }

    fun question(packet: ByteArray): Question {
        require(packet.size >= 12 && u16(packet, 4) == 1)
        var position = 12
        var end = -1
        var length = 1
        val seen = HashSet<Int>()
        val labels = mutableListOf<String>()
        while (true) {
            require(position in packet.indices && seen.add(position))
            val before = position
            val count = packet[position++].toInt() and 255
            if (count == 0) break
            if (count and 0xc0 == 0xc0) {
                require(position < packet.size)
                val target = ((count and 63) shl 8) or (packet[position++].toInt() and 255)
                require(target in 12 until before)
                if (end < 0) end = position
                position = target
            } else {
                require(count in 1..63 && position + count <= packet.size)
                length += count + 1
                require(length <= 255)
                labels += String(packet, position, count, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
                position += count
            }
        }
        if (end >= 0) position = end
        return Question(labels.joinToString("."), u16(packet, position), u16(packet, position + 2))
    }

    fun responseMatches(query: ByteArray, response: ByteArray): Boolean = runCatching {
        require(id(query) == id(response))
        val flags = u16(response, 2)
        require(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0200 == 0)
        require(question(query) == question(response))
        true
    }.getOrDefault(false)

    private fun u16(bytes: ByteArray, at: Int): Int {
        require(at >= 0 && at + 1 < bytes.size)
        return ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    }
}
