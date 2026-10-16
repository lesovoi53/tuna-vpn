package com.wireturn.app.domain

import java.net.URI

/** Versioned, additive local app-routing command. No URLs or wildcards. */
object AppExclusionsLink {
    data class Command(val bypass: Boolean, val packages: Set<String>)
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun recognizes(text: String) = text.trim().startsWith("tuna://app-exclusions/", true)

    fun parse(text: String): Set<String> = command(text).packages

    fun command(text: String): Command {
        require(text.length <= 32_768)
        val uri = URI(text.trim())
        require(uri.scheme.equals("tuna", true) && uri.rawAuthority == "app-exclusions")
        require(uri.rawPath == "/v1" && uri.rawFragment == null)
        val query = requireNotNull(uri.rawQuery)
        require('%' !in query)
        val fields = query.split('&').map { it.split('=', limit = 2).also { pair -> require(pair.size == 2) } }
        require(fields.map { it[0] }.distinct().size == fields.size)
        require(fields.all { it[0] in setOf("mode", "packages") })
        val values = fields.associate { it[0] to it[1] }
        val bypass = when (values["mode"] ?: "bypass") {
            "bypass" -> true
            "vpn" -> false
            else -> throw IllegalArgumentException("Unknown app routing mode")
        }
        val entries = requireNotNull(values["packages"]).split(',')
        require(entries.size in 1..100)
        require(entries.all { it.length <= 255 && packageName.matches(it) })
        return Command(bypass, entries.toSet())
    }

}
