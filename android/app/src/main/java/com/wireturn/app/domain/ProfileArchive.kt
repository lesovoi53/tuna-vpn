package com.wireturn.app.domain

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

/** Plain JSON may contain the whole profile collection, just like an archive. */
internal fun readProfileText(input: InputStream, maxBytes: Int = 32 * 1024 * 1024): String {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        // Read at most one byte beyond the limit, including streams without a known size.
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Файл профилей превышает допустимый размер" }
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
}

/** Decode the whole archive before any import mutation. Limits include ignored entries. */
internal fun readProfileArchive(input: InputStream, maxEntryBytes: Int = 4 * 1024 * 1024,
    maxTotalBytes: Int = 32 * 1024 * 1024, maxEntries: Int = 2000): List<Pair<String?, String>> {
    val result = mutableListOf<Pair<String?, String>>()
    var total = 0L
    var count = 0
    ZipInputStream(input).use { zip ->
        val buffer = ByteArray(8192)
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++count <= maxEntries) { "Слишком много файлов в архиве профилей" }
            val json = !entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)
            val output = if (json) ByteArrayOutputStream() else null
            var size = 0L
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                size += read; total += read
                require(size <= maxEntryBytes && total <= maxTotalBytes) { "Архив профилей превышает допустимый размер" }
                output?.write(buffer, 0, read)
            }
            if (output != null) result += entry.name to
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }
    }
    return result
}
