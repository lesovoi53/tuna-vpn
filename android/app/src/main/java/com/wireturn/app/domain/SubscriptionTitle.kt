package com.wireturn.app.domain

import java.nio.ByteBuffer
import java.util.Base64

object SubscriptionTitle {
    fun decode(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 } ?: return null
        val text = if (raw.startsWith("base64:", ignoreCase = true)) {
            runCatching {
                val encoded = raw.substring(7).trim()
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { Base64.getUrlDecoder().decode(encoded) }
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull() ?: return null
        } else raw
        return text.filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
            .trim().take(200).takeIf { it.isNotEmpty() }
    }
}
