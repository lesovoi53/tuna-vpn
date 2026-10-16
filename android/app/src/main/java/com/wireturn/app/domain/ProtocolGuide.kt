package com.wireturn.app.domain

import androidx.annotation.StringRes
import com.wireturn.app.R

/** User-facing connections, not the list of protocols compiled into native binaries. */
object ProtocolGuide {
    data class Entry(val id: String, val name: String, @param:StringRes val category: Int,
        @param:StringRes val description: Int, val source: String)
    val entries = listOf(
        Entry("vless", "VLESS", R.string.protocol_guide_vpn, R.string.protocol_guide_vless, "https://xtls.github.io/en/development/protocols/vless.html"),
        Entry("hysteria2", "Hysteria2", R.string.protocol_guide_vpn, R.string.protocol_guide_hysteria2, "https://hysteria.network/docs/developers/Protocol/"),
        Entry("snell", "Snell", R.string.protocol_guide_vpn, R.string.protocol_guide_snell, "https://manual.nssurge.com/policies/snell.html"),
        Entry("mieru", "Mieru", R.string.protocol_guide_vpn, R.string.protocol_guide_mieru, "https://github.com/enfein/mieru/blob/b9bbc4161ae97b0d594d4d37ea7e67b30879e74e/README.md"),
        Entry("trojan", "Trojan", R.string.protocol_guide_vpn, R.string.protocol_guide_trojan, "https://trojan-gfw.github.io/trojan/protocol"),
        Entry("shadowsocks", "Shadowsocks", R.string.protocol_guide_vpn, R.string.protocol_guide_shadowsocks, "https://shadowsocks.org/doc/what-is-shadowsocks.html"),
        Entry("tuic", "TUIC", R.string.protocol_guide_vpn, R.string.protocol_guide_tuic, "https://github.com/tuic-protocol/tuic/blob/8e118f242f24a17a9f487dc344cc50d7e63e557e/README.md"),
        Entry("anytls", "AnyTLS", R.string.protocol_guide_vpn, R.string.protocol_guide_anytls, "https://github.com/anytls/anytls-go/blob/fd6167acd6d73b9fa3e607659951847fbc9e6c50/readme.md"),
        Entry("naive", "NaiveProxy", R.string.protocol_guide_vpn, R.string.protocol_guide_naive, "https://github.com/klzgrad/naiveproxy/blob/v150.0.7871.63-1/README.md"),
        Entry("qwdtt", "qWDTT", R.string.protocol_guide_bypass, R.string.protocol_guide_qwdtt, "https://github.com/SpaceNeuroX/proxy-turn-vk-android/blob/fae121efc3ef57b633516601d3c0d6b1be1fde7c/README.md"),
        Entry("masterdns", "MasterDnsVPN / StormDNS", R.string.protocol_guide_bypass, R.string.protocol_guide_masterdns, "https://github.com/masterking32/MasterDnsVPN/blob/acbf1c61f90786f41b975d2e2f616afbce292b29/README.MD"),
        Entry("webdav", "WebDAV", R.string.protocol_guide_bypass, R.string.protocol_guide_webdav, "https://github.com/spkprsnts/webdav-tunnel/blob/b1af4c05eb80fd29a6f676be894fa61cd12a3dec/README.md"),
        Entry("awg", "AmneziaWG", R.string.protocol_guide_vpn, R.string.protocol_guide_awg, "https://github.com/amnezia-vpn/amneziawg-go"),
        Entry("trusttunnel", "TrustTunnel", R.string.protocol_guide_vpn, R.string.protocol_guide_trusttunnel, "https://github.com/TrustTunnel/TrustTunnel"),
        Entry("wireguard", "WireGuard", R.string.protocol_guide_vpn, R.string.protocol_guide_wireguard, "https://www.wireguard.com/protocol/")
    )
}
