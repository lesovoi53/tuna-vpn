package com.wireturn.app.domain

import java.nio.ByteBuffer
import java.util.Base64

/** Separate the self-contained routing command from ordinary URI-list entries. */
internal object AppRoutingSubscription {
    data class Result(val content: String, val command: AppExclusionsLink.Command?,
        val fullRouting: TunaRoutingLink.Command? = null, val legacyRouting: String? = null)
    fun extract(body: String): Result {
        require(body.length <= 4 * 1024 * 1024)
        val text = if (body.contains("://")) body else runCatching {
            val compact = body.filterNot(Char::isWhitespace)
            val bytes = runCatching { Base64.getDecoder().decode(compact) }
                .getOrElse { Base64.getUrlDecoder().decode(compact) }
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return Result(body, null)
        var command: AppExclusionsLink.Command? = null
        var fullRouting: TunaRoutingLink.Command? = null
        var legacyRouting: String? = null
        fun acceptApps(next: AppExclusionsLink.Command) {
            require(command == null || command == next) { "Conflicting app routing commands" }
            command = next
        }
        val remaining = text.lines().filter { line ->
            when {
                TunaRoutingLink.recognizes(line) -> {
                    val next = TunaRoutingLink.parse(line)
                    require(fullRouting == null || fullRouting == next) { "Conflicting full routing commands" }
                    fullRouting = next
                    next.apps?.let(::acceptApps)
                    false
                }
                AppExclusionsLink.recognizes(line) -> {
                    acceptApps(AppExclusionsLink.command(line))
                    false
                }
                line.trim().startsWith("happ://routing/") -> {
                    val next = line.trim()
                    SubscriptionPolicyParser.parseHapp(next)
                    require(legacyRouting == null || legacyRouting == next) { "Conflicting Happ routing commands" }
                    legacyRouting = next
                    false
                }
                else -> true
            }
        }
        return if (command == null && fullRouting == null && legacyRouting == null) Result(body, null)
            else Result(remaining.joinToString("\n"), command, fullRouting, legacyRouting)
    }
}
