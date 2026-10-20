package com.wireturn.app.data.kernel

/** Repair literal Markdown escapes at URI boundaries, never percent-decoded credentials. */
object VpnImportText {
    fun normalize(value: String): String {
        val scheme = listOf("snell", "vless", "tt", "trusttunnel").firstOrNull {
            value.startsWith("$it://", true) || value.startsWith("$it\\://", true)
        } ?: return value
        val input = if (value.startsWith("$scheme\\://", true))
            value.removeRange(scheme.length, scheme.length + 1) else value
        val start = input.indexOf("://") + 3
        val end = input.indexOfAny(charArrayOf('/', '?', '#'), start).takeIf { it >= 0 } ?: input.length
        val authority = input.substring(start, end).replace("\\_", "_").replace("\\@", "@")
        val repaired = input.substring(0, start) + authority + input.substring(end)
        val query = repaired.indexOf('?')
        val fragment = repaired.indexOf('#').takeIf { it >= 0 } ?: repaired.length
        if (query < 0 || query > fragment) return repaired
        val parameters = repaired.substring(query + 1, fragment).split('&').joinToString("&") { part ->
            val separator = part.indexOf('=')
            if (separator < 0) part else
                part.substring(0, separator).replace("\\_", "_") + part.substring(separator)
        }
        return repaired.substring(0, query + 1) + parameters + repaired.substring(fragment)
    }
}
