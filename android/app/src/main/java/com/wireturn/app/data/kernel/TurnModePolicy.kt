package com.wireturn.app.data.kernel

object TurnModePolicy {
    fun hasHashes(values: List<String>, maxLength: Int = 4096) = values.any { it.trim().let { h -> h.length in 16..maxLength && h.none(Char::isWhitespace) } }
    fun edit(config: QwdttConfig, values: List<String>): QwdttConfig {
        val hashes = values.map { value ->
            val text = value.trim()
            if (text.startsWith("https://") || text.startsWith("http://"))
                runCatching { java.net.URI(text).path.substringAfterLast('/').trim() }.getOrDefault(text)
            else text
        }
        return config.copy(vkHashes = hashes.joinToString(","), accountAuth = !hasHashes(hashes, 2000))
    }
    fun open(config: QwdttConfig) = if (!hasHashes(config.hashList, 2000)) config.copy(accountAuth = true) else config
}
