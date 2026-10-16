package com.wireturn.app.data

import com.google.gson.JsonObject
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Literal IP avoids resolving the resolver through the tunnel it is bootstrapping. */
data class BootstrapDns(val transport: String, val address: String, val port: Int) {
    fun xrayAddress(): String = "tcp://" +
        (if (':' in address) "[$address]" else address) + ":$port"

    fun singBoxServer(tag: String = "bootstrap") = JsonObject().apply {
        addProperty("type", transport); addProperty("tag", tag)
        addProperty("server", address); addProperty("server_port", port)
    }

    companion object {
        fun parse(value: String): BootstrapDns? {
            if (value.isBlank()) return null // Retain old per-adapter defaults until explicitly configured.
            val uri = URI(value.trim())
            require(uri.scheme == "tcp" && uri.host != null && uri.rawUserInfo == null &&
                uri.path.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)) { "Общий Bootstrap DNS: tcp://IP:порт (TCP поддерживают оба адаптера)" }
            val address = uri.host.removeSurrounding("[", "]")
            val literal = if (':' in address) address.matches(Regex("[0-9a-fA-F:]+")) &&
                runCatching { InetAddress.getByName(address) is Inet6Address }.getOrDefault(false)
            else address.split('.').let { parts -> parts.size == 4 && parts.all {
                it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255
            } }
            require(literal) { "Bootstrap DNS требует IP, а не домен" }
            return BootstrapDns(uri.scheme, address, uri.port.takeIf { it > 0 } ?: 53)
        }
    }
}
