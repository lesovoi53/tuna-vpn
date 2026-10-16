package com.wireturn.app.domain

import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy

/** A measurement for one destination, never a statement about every server-side route. */
data class ExitObservation(val ip: String, val country: String?, val warp: Boolean?, val checkedAt: Long) {
    companion object {
        fun parse(body: String, now: Long): ExitObservation? {
            if (body.length > 8192) return null
            val fields = mutableMapOf<String, String>()
            for (line in body.lineSequence()) {
                val key = line.substringBefore('=')
                if (key !in setOf("ip", "loc", "warp")) continue
                if ('=' !in line || fields.put(key, line.substringAfter('=').trim()) != null) return null
            }
            val ip = fields["ip"] ?: return null
            val valid = if (':' in ip) ip.matches(Regex("[0-9a-fA-F:.]+")) &&
                runCatching { InetAddress.getByName(ip) is Inet6Address }.getOrDefault(false)
            else ip.split('.').let { parts -> parts.size == 4 && parts.all {
                it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255
            } }
            if (!valid) return null
            return ExitObservation(ip, fields["loc"]?.takeIf { it.matches(Regex("[A-Z]{2}")) },
                when (fields["warp"]) { "on", "plus" -> true; "off" -> false; else -> null }, now)
        }

        suspend fun fetch(proxy: Proxy): ExitObservation {
            val body = ProxyHttpText.fetch("https://www.cloudflare.com/cdn-cgi/trace", proxy, timeoutMs = 5000)
            return requireNotNull(parse(body, System.currentTimeMillis())) { "Invalid trace" }
        }
    }
}

data class ExitObservationState(val loading: Boolean = false, val observation: ExitObservation? = null, val unavailable: Boolean = false)
