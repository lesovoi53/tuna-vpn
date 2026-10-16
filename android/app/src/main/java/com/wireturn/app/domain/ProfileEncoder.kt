package com.wireturn.app.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

object ProfileEncoder {
    // Bound both the transport allocation and its expanded output, including direct deep links.
    const val MAX_DECODED_BYTES = 4 * 1024 * 1024
    private const val MAX_COMPRESSED_BYTES = MAX_DECODED_BYTES + 64 * 1024
    private const val MAX_ENCODED_CHARS = 6 * 1024 * 1024
    // Sharing goes through an Android Intent. Keep generous transaction headroom;
    // larger sets use a file attachment instead of a large inline string.
    private const val MAX_SHARE_LINK_CHARS = 128 * 1024

    fun encodeForSharing(json: String): String? {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_DECODED_BYTES) return null
        return encode(json).takeIf { it.length <= MAX_SHARE_LINK_CHARS }
    }

    fun encode(json: String): String {
        val input = json.toByteArray(Charsets.UTF_8)
        require(input.size <= MAX_DECODED_BYTES) { "Profile exceeds the share-link size limit" }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buf)
                check(count > 0) { "Incomplete compressed profile" }
                bos.write(buf, 0, count)
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
        } finally {
            deflater.end()
        }
    }

    fun decode(encoded: String): String? {
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null
        return try {
            // Preserve legacy line-wrapped Base64, without accepting arbitrary invalid characters.
            val compact = encoded.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
            if (compact.isEmpty() || compact.length > ((MAX_COMPRESSED_BYTES + 2) / 3) * 4) return null
            val compressed = Base64.getUrlDecoder().decode(compact)
            if (compressed.isEmpty() || compressed.size > MAX_COMPRESSED_BYTES) return null
            val inflater = Inflater()
            try {
                inflater.setInput(compressed)
                val bos = ByteArrayOutputStream(minOf(compressed.size, 8192))
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        // Missing input, a required dictionary, or stalled decoding is not success.
                        if (!inflater.finished()) return null
                    } else {
                        if (count > MAX_DECODED_BYTES - bos.size()) return null
                        bos.write(buf, 0, count)
                    }
                }
                if (inflater.remaining != 0) return null
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bos.toByteArray())).toString()
            } finally {
                inflater.end()
            }
        } catch (_: Exception) {
            null
        }
    }
}
