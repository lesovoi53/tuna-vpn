package com.wireturn.app

/** Logs are support artifacts. Full connection data belongs only in explicit profile export. */
object DiagnosticRedactor {
    private val url = Regex("[A-Za-z][A-Za-z0-9+.-]*://[^\\s\"'<>]+")
    private val authorization = Regex("(?im)(\\b(?:proxy-)?authorization\\s*[:=]\\s*)[^\\r\\n]+")
    private val cookies = Regex("(?im)([\"']?\\b(?:set-cookie|cookies?|remixsid|remixsid6)[\"']?\\s*[:=]\\s*)[^\\r\\n]+")
    private val secret = Regex("(?i)([\"']?\\b(?:password|passwd|pass|private_?key|pre_?shared_?key|encryption_?key|header_protection_key|psk|userkey|username|proxy_user|socks_user|token|access_?token|refresh_?token|secret|hashes|proxy_pass|socks_pass)[\"']?\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"])*\"|'[^']*'|[^\\s,;]+)")
    private val flag = Regex("(?i)((?:--?)(?:password|passwd|pass|token|secret|private-key|proxy-pass|socks-pass|username|proxy-user|socks-user|psk|userkey)\\s+)(?:\"[^\"]*\"|'[^']*'|\\S+)")
    private val inboundUser = Regex("(?i)(\\binbound/(?:socks|http|mixed)\\[[^\\]\\r\\n]*\\]:\\s*)\\[[^\\]\\r\\n]+\\]")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val opaque = Regex("(?<![A-Za-z0-9_+/-])[A-Za-z0-9_+/-]{32,}={0,2}(?![A-Za-z0-9_+/-])")
    fun clean(message: String): String {
        var value = url.replace(message.take(32768), "<redacted-url>")
        value = authorization.replace(value) { it.groupValues[1] + "<redacted>" }
        value = cookies.replace(value) { it.groupValues[1] + "<redacted>" }
        value = secret.replace(value) { it.groupValues[1] + "\"<redacted>\"" }
        value = flag.replace(value) { it.groupValues[1] + "<redacted>" }
        value = inboundUser.replace(value) { it.groupValues[1] + "[<redacted-user>]" }
        value = uuid.replace(value, "<redacted-id>")
        return opaque.replace(value, "<redacted-token>")
    }
}
