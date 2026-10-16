package com.wireturn.app.kernel

import com.wireturn.app.data.*
import java.net.URI

/** Only checks an enabled chain. Saved but unused links cannot prevent a standalone tunnel. */
object ChainCompatibility {
    fun validate(client: ClientConfig, xray: XrayConfig, vless: VlessConfig, settings: XraySettings) {
        val active = xray.enabled && xray.protocol == XrayConfiguration.VLESS && (vless.isSocks5Chain || vless.isDualRoute)
        if (!active) return
        val uri = runCatching { URI(vless.vlessLink) }.getOrNull()
        require(uri != null && uri.host != null) { "Некорректная ссылка цепочки" }
        val ports = mutableSetOf(client.socksAddr.substringAfterLast(':').toIntOrNull(), settings.socksBindAddress.substringAfterLast(':').toIntOrNull(), settings.httpBindAddress.substringAfterLast(':').toIntOrNull())
        val host = uri.host.removeSurrounding("[", "]").lowercase()
        require(!(host in setOf("localhost", "::1", "0.0.0.0", "::", "::ffff:127.0.0.1") || host.startsWith("127.")) || uri.port !in ports) {
            "Цепочка ссылается на собственный локальный прокси и создаёт цикл"
        }
        if (client.kernelVariant == KernelVariant.WEBDAV) {
            try { WebdavRuntimeConfig.validateChain(true, vless.vlessLink) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("WebDAV: цепочка должна использовать TCP; Hysteria2, TUIC и QUIC не поддерживаются") }
        }
    }
}
