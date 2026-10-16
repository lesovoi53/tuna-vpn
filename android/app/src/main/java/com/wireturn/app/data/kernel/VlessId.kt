package com.wireturn.app.data.kernel

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Xray VLESS UUIDv5 mapping with the nil namespace; limits are UTF-8 byte limits. */
object VlessId {
    fun canonical(value: String): String {
        val text = value.toByteArray(Charsets.UTF_8)
        if (text.size in 1..30) {
            val hash = MessageDigest.getInstance("SHA-1").apply { update(ByteArray(16)) }.digest(text)
            hash[6] = ((hash[6].toInt() and 15) or 0x50).toByte()
            hash[8] = ((hash[8].toInt() and 63) or 0x80).toByte()
            val bytes = ByteBuffer.wrap(hash)
            return UUID(bytes.long, bytes.long).toString()
        }
        val compact = value.replace("-", "")
        require((value.length == 32 || value.length == 36) && compact.length == 32 &&
            compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "VLESS ID: UUID или строка длиной 1–30 байт UTF-8"
        }
        val normalized = "${compact.take(8)}-${compact.substring(8, 12)}-${compact.substring(12, 16)}-${compact.substring(16, 20)}-${compact.substring(20)}"
        require(value.length == 32 || value.equals(normalized, true)) { "Неверный UUID VLESS" }
        return normalized.lowercase()
    }
}
