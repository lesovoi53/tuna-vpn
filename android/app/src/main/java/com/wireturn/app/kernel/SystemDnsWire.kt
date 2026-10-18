package com.wireturn.app.kernel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

/** Restricted wire adapter for pre-29 Network.getAllByName; never invent NXDOMAIN. */
internal object SystemDnsWire {
    data class Question(val name: String, val type: Int, val end: Int)
    private fun word(data: ByteArray, at: Int) = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
    fun question(data: ByteArray): Question {
        require(data.size in 17..4096 && word(data, 2) and 0xf800 == 0 && word(data, 4) == 1)
        val labels = mutableListOf<String>()
        var at = 12
        while (true) {
            require(at < data.size)
            val size = data[at++].toInt() and 255
            if (size == 0) break
            require(size in 1..63 && at + size < data.size)
            val label = data.copyOfRange(at, at + size)
            require(label.all { (it.toInt() and 255) in 33..126 && it != '.'.code.toByte() })
            labels += label.toString(Charsets.US_ASCII)
            at += size
        }
        require(at + 4 <= data.size && word(data, at + 2) == 1)
        val type = word(data, at)
        require(type == 1 || type == 28)
        val name = labels.joinToString(".")
        require(name.isNotEmpty() && name.length <= 253)
        return Question(name, type, at + 4)
    }

    fun answer(query: ByteArray, question: Question, addresses: Array<InetAddress>): ByteArray {
        val chosen = addresses.map { it.address }.filter { it.size == if (question.type == 1) 4 else 16 }.take(64)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(word(query, 0)); out.writeShort(0x8080 or (word(query, 2) and 0x0100))
            out.writeShort(1); out.writeShort(chosen.size); out.writeShort(0); out.writeShort(0)
            out.write(query, 12, question.end - 12)
            for (address in chosen) {
                out.writeShort(0xc00c); out.writeShort(question.type); out.writeShort(1)
                out.writeInt(0); out.writeShort(address.size); out.write(address)
            }
        }
        return bytes.toByteArray()
    }
}
