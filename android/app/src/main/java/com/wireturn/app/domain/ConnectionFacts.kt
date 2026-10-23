package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.R
import java.net.URI
import java.net.URLDecoder

/** Explicit allow-list: never render a URI, arbitrary native JSON, password or token. */
data class ConnectionFacts(val protocol: String, val server: String, val rows: List<ConnectionFact>) {
    companion object {
        fun endpoint(value: String): String = runCatching {
            val uri = URI(if ("://" in value) value else "endpoint://$value")
            val host = uri.host ?: return@runCatching ""
            host + if (uri.port > 0) ":${uri.port}" else ""
        }.getOrDefault("")

        fun from(config: KernelConfig): ConnectionFacts = when (config) {
            is KernelConfig.Vpn -> {
                val c = config.config
                val q = runCatching { URI(c.uri).rawQuery.orEmpty().split('&').filter { '=' in it }.groupBy(
                    { URLDecoder.decode(it.substringBefore('='), "UTF-8") },
                    { URLDecoder.decode(it.substringAfter('='), "UTF-8") }) }.getOrDefault(emptyMap())
                val rows = mutableListOf(ConnectionFact(R.string.cd_core, c.engineLabel))
                val fields = linkedMapOf(
                    "type" to R.string.cd_transport,
                    "security" to R.string.cd_security,
                    "sni" to R.string.cd_sni,
                    "serverName" to R.string.cd_sni,
                    "fp" to R.string.cd_fingerprint,
                    "mode" to R.string.cd_xhttp_mode,
                    "alpn" to R.string.cd_alpn,
                    "flow" to R.string.cd_flow,
                    "version" to R.string.cd_protocol_version,
                    "obfs-mode" to R.string.cd_obfs,
                    "obfs" to R.string.cd_obfs,
                    "reuse" to R.string.cd_reuse,
                    "tfo" to R.string.cd_tfo,
                    "network" to R.string.cd_network,
                    "congestion_control" to R.string.cd_congestion,
                    "udp_relay_mode" to R.string.cd_udp,
                    "protocol" to R.string.cd_transport,
                    "port" to R.string.cd_ports,
                    "multiplexing" to R.string.cd_mux,
                    "mtu" to R.string.cd_mtu,
                    "method" to R.string.cd_method)
                if (c.protocol == "snell") {
                    fields.remove("tfo")
                    fields.remove("reuse")
                    val effective = runCatching { com.wireturn.app.kernel.SingBoxConfig.outbound(c) }.getOrNull()
                    effective?.get("reuse")?.asBoolean?.let { enabled ->
                        rows += ConnectionFact(R.string.cd_reuse, valueResource =
                            if (enabled) R.string.cd_enabled else R.string.cd_disabled)
                    }
                    runCatching { effective?.get("tcp_fast_open")?.asBoolean ?: false }
                        .getOrNull()?.let { enabled -> rows += ConnectionFact(R.string.cd_tfo, valueResource =
                            if (enabled) R.string.cd_enabled
                            else if (com.wireturn.app.kernel.SingBoxConfig.snellTfoSuppressed(c)) R.string.snell_tfo_disabled_compatibility
                            else R.string.cd_disabled) }
                }
                for ((key, label) in fields) q[key]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let {
                    rows += ConnectionFact(label, it.joinToString(", ").take(512), sensitive = key in setOf("sni", "serverName", "port"))
                }
                if (c.protocol in setOf("hysteria2", "tuic") && rows.none { it.label == R.string.cd_transport }) rows += ConnectionFact(R.string.cd_transport, "QUIC")
                if (c.protocol == "naive") rows += ConnectionFact(R.string.cd_transport, "HTTPS · Chromium")
                if ((c.options?.forEngine(com.wireturn.app.data.kernel.CoreCapabilities.resolve(c))?.size() ?: 0) > 0) {
                    rows += ConnectionFact(R.string.cd_extra, valueResource = R.string.cd_extra_note)
                }
                ConnectionFacts(c.protocol.uppercase(), endpoint(c.uri), rows)
            }
            is KernelConfig.Webdav -> config.config.let { c -> ConnectionFacts("WebDAV", endpoint(c.webdav), listOf(
                ConnectionFact(R.string.cd_storage, (c.backends.size + 1).toString()),
                ConnectionFact(R.string.cd_encryption, "AES-256-GCM", valueResource = if(c.encrypt) null else R.string.cd_disabled),
                ConnectionFact(R.string.cd_poll, "${c.pollMin} – ${c.pollMax}"),
                ConnectionFact(R.string.cd_timeout, c.timeout), ConnectionFact(R.string.cd_chunk, c.chunkSize),
                ConnectionFact(R.string.cd_write_read, "${c.puts} / ${c.readMin}–${c.readMax}"))) }
            is KernelConfig.Qwdtt -> config.config.let { c -> ConnectionFacts("qWDTT", endpoint(c.peer), listOf(
                ConnectionFact(R.string.cd_workers, c.effectiveWorkers.toString()),
                ConnectionFact(R.string.cd_turn, if(c.turnTcp) "TCP" else "UDP"),
                ConnectionFact(R.string.cd_obfs, c.obfsMode),
                ConnectionFact(R.string.cd_dtls, valueResource = if(c.noTls) R.string.cd_disabled else R.string.cd_enabled))) }
            is KernelConfig.MasterDns -> config.config.let { c ->
                val labels = mapOf(
                    "min_upload_mtu" to R.string.cd_min_upload_mtu, "max_upload_mtu" to R.string.cd_max_upload_mtu,
                    "min_download_mtu" to R.string.cd_min_download_mtu, "max_download_mtu" to R.string.cd_max_download_mtu,
                    "rx_tx_workers" to R.string.cd_rx_tx_workers, "resolver_balancing_strategy" to R.string.cd_resolver_balancing_strategy,
                    "upload_compression_type" to R.string.cd_upload_compression_type, "download_compression_type" to R.string.cd_download_compression_type)
                ConnectionFacts("MasterDNS / StormDNS", c.domains.joinToString(", "),
                    listOf(ConnectionFact(R.string.cd_resolvers,
                        c.domains.joinToString("\n\n") { domain ->
                            domain + "\n" + c.resolverList(domain).joinToString("\n").ifEmpty { "—" }
                        }, sensitive = true),
                        ConnectionFact(R.string.cd_encryption_method, c.encryption.toString())) +
                        c.settings.mapNotNull { (key, value) -> labels[key]?.let { ConnectionFact(it, value) } })
            }
            else -> ConnectionFacts("—", "", emptyList())
        }
    }
}
