package com.wireturn.app.kernel

import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.XraySettings

/** Allocate separate listeners before either service starts; do not depend on startup order. */
object LocalProxyPorts {
    fun resolve(client: ClientConfig, settings: XraySettings): XraySettings {
        val reserved = mutableSetOf<Int>()
        fun reserve(address: String) { address.substringAfterLast(':').toIntOrNull()?.let { reserved.add(it) } }
        reserve(client.socksAddr)
        reserve(client.listenAddr)
        fun allocate(address: String): String {
            if (address.isBlank()) return address
            val port = address.substringAfterLast(':').toInt()
            val selected = if (port in reserved) (1089..65535).first { it !in reserved } else port
            reserved.add(selected)
            return address.substringBeforeLast(':') + ":$selected"
        }
        val socks = allocate(settings.socksBindAddress)
        val http = allocate(settings.httpBindAddress)
        return settings.copy(socksBindAddress = socks, httpBindAddress = http)
    }
}
