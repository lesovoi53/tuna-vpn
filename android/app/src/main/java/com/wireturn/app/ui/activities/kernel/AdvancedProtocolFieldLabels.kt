package com.wireturn.app.ui.activities.kernel

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import com.wireturn.app.data.kernel.VpnEditorField

internal val awgEditorSections = listOf(
    R.string.awg_section_connection to listOf("server", "port", "local_address", "dns", "allowed_ips", "mtu", "persistent_keepalive_interval", "peers"),
    R.string.awg_section_keys to listOf("private_key", "public_key", "pre_shared_key"),
    R.string.awg_section_obfuscation to listOf("enable_amnezia", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4"),
    R.string.awg_section_signatures to listOf("i1", "i2", "i3", "i4", "i5"),
    R.string.awg_section_extended to listOf("header_protection_key", "content_padding_addition", "rekey_after_time", "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts", "random_trailers", "disable_cookies")
)

internal fun awgFieldSection(field: VpnEditorField): Int = awgEditorSections.indexOfFirst {
    (field.queryName ?: field.key) in it.second
}.let { if (it < 0) awgEditorSections.size else it }

internal fun awgFieldOrder(field: VpnEditorField): Int = awgEditorSections.getOrNull(awgFieldSection(field))
    ?.second?.indexOf(field.queryName ?: field.key) ?: Int.MAX_VALUE

@Composable
internal fun advancedProtocolFieldLabel(protocol: String, field: VpnEditorField): String {
    if (protocol !in setOf("awg", "wireguard", "trusttunnel", "snell")) return field.label
    val key = (field.queryName ?: field.key).replace('-', '_')
    val id = when (key) {
        "server" -> R.string.advanced_field_server
        "port" -> R.string.advanced_field_port
        "username" -> R.string.advanced_field_username
        "password" -> R.string.advanced_field_password
        "credential" -> R.string.advanced_field_psk
        "version" -> R.string.advanced_field_snell_version
        "obfs_mode" -> R.string.advanced_field_obfs_mode
        "obfs_host" -> R.string.advanced_field_obfs_host
        "reuse" -> R.string.advanced_field_reuse
        "userkey" -> R.string.advanced_field_userkey
        "tfo" -> R.string.advanced_field_tfo
        "udp_relay" -> R.string.advanced_field_udp_relay
        "private_key" -> R.string.advanced_field_private_key
        "public_key" -> R.string.advanced_field_public_key
        "pre_shared_key" -> R.string.advanced_field_pre_shared_key
        "local_address" -> R.string.advanced_field_local_address
        "allowed_ips" -> R.string.advanced_field_allowed_ips
        "dns" -> R.string.advanced_field_dns
        "mtu" -> R.string.advanced_field_mtu
        "persistent_keepalive_interval" -> R.string.advanced_field_persistent_keepalive_interval
        "enable_amnezia" -> R.string.advanced_field_enable_amnezia
        "peers" -> R.string.advanced_field_peers
        "jc" -> R.string.advanced_field_jc
        "jmin" -> R.string.advanced_field_jmin
        "jmax" -> R.string.advanced_field_jmax
        "header_protection_key" -> R.string.advanced_field_header_protection_key
        "content_padding_addition" -> R.string.advanced_field_content_padding_addition
        "rekey_after_time" -> R.string.advanced_field_rekey_after_time
        "rekey_timeout" -> R.string.advanced_field_rekey_timeout
        "reject_after_time" -> R.string.advanced_field_reject_after_time
        "keepalive_timeout" -> R.string.advanced_field_keepalive_timeout
        "max_handshake_attempts" -> R.string.advanced_field_max_handshake_attempts
        "random_trailers" -> R.string.advanced_field_random_trailers
        "disable_cookies" -> R.string.advanced_field_disable_cookies
        "client_random_prefix" -> R.string.advanced_field_client_random_prefix
        "use_cronet_https" -> R.string.advanced_field_use_cronet_https
        "use_cronet_quic" -> R.string.advanced_field_use_cronet_quic
        "force_quic" -> R.string.advanced_field_force_quic
        "health_check" -> R.string.advanced_field_health_check
        "quic" -> R.string.advanced_field_quic
        "congestion_controller" -> R.string.advanced_field_congestion_controller
        "network" -> R.string.advanced_field_network
        "security" -> R.string.advanced_field_security
        "alpn" -> R.string.advanced_field_alpn
        "insecure" -> R.string.advanced_field_insecure
        "certificate" -> R.string.advanced_field_certificate
        "certificate_public_key_sha256" -> R.string.advanced_field_certificate_public_key_sha256
        "fp" -> R.string.advanced_field_fp
        "mode" -> R.string.advanced_field_mode
        "quic_proxy_mode" -> R.string.advanced_field_quic_proxy_mode
        "s1" -> R.string.advanced_field_s1
        "h1" -> R.string.advanced_field_h1
        "s2" -> R.string.advanced_field_s2
        "h2" -> R.string.advanced_field_h2
        "s3" -> R.string.advanced_field_s3
        "h3" -> R.string.advanced_field_h3
        "s4" -> R.string.advanced_field_s4
        "h4" -> R.string.advanced_field_h4
        "i1" -> R.string.advanced_field_i1
        "i2" -> R.string.advanced_field_i2
        "i3" -> R.string.advanced_field_i3
        "i4" -> R.string.advanced_field_i4
        "i5" -> R.string.advanced_field_i5
        "allowInsecure", "allow_insecure" -> R.string.advanced_field_insecure
        "congestion_control", "quic_congestion_control" -> R.string.advanced_field_congestion_controller
        else -> null
    }
    return id?.let { stringResource(it) } ?: field.label
}
