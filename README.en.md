# TUNA VPN

An Android VPN client built around Xray, sing-box-extended and Mieru, with a standalone Windows routing editor. [Russian documentation](README.md) is the primary maintained guide.

[Stable release 1.65.3](https://github.com/lesovoi53/tuna-vpn/releases/tag/v1.65.3) · [Build](docs/development/build.md) · [Licenses](docs/licenses.md)

Release 1.65.3 includes per-domain MasterDnsVPN resolvers and fixes for connection lifecycle, subscription refresh and profile import. [Changes and verification](docs/releases/1.65.3.md).

## Supported protocols

VPN profiles: **VLESS, Hysteria2, Snell, Mieru, Trojan, Shadowsocks, TUIC, AnyTLS, NaiveProxy, WireGuard, AmneziaWG and TrustTunnel**. Dedicated connection types: **qWDTT, MasterDnsVPN / StormDNS and WebDAV tunneling**. Full Xray JSON import is also supported; it is a configuration format, not a separate protocol. Compatibility depends on the profile parameters and bundled core. [Protocol details](docs/user/protocols.md).

## DNS tunnel server

**[TUNA DNS Tunnel](https://github.com/lesovoi53/tuna-dns-tunnel)** is the standalone Linux server installer for MasterDnsVPN / StormDNS connections. It does not require X-Manager. Its console manages domains, encryption, exported client settings and Direct or an existing external SOCKS5 proxy.

Export a separate `stormdns://` link per DNS zone or one `tuna://` link containing all domains of the same server, then import it into TUNA VPN. Multiple domains on one server are distinct from a group of independent servers. Public recursive resolvers require delegated DNS zones and a reachable authoritative server on UDP port 53. See the server repository for installation, upstream attribution and validation limits; compatibility depends on the client version.

## CDN and Cloudflare categories

List placement uses the **individual profile name**, including names supplied by a subscription, not automatic IP/DNS/CDN detection:

| Destination | Profile-name rule | Example |
| --- | --- | --- |
| Обход БС → CDN | An ordinary VPN profile contains the Russian phrase `обход бс` | `Москва обход БС` |
| VPN → VLESS CF | A VLESS profile contains the separate Latin tag `CF` | `Germany [CF]` |
| Normal VPN protocol section | Neither applicable naming rule matches | `Germany VLESS` |

Matching is case-insensitive. The bypass phrase requires whitespace between its words; the CF tag must not touch letters, digits or `_`. `CDN` alone does not select the bypass category. `Cloudflare`, `CF1`, `my_cf` and Cyrillic `КФ` do not match `CF`. If both tags are present, **Обход БС → CDN takes precedence**. Only VLESS gets the separate VLESS CF section. qWDTT, MasterDnsVPN / StormDNS and WebDAV are already in Обход БС by connection type.

These labels do not change the protocol or turn an ordinary endpoint into a CDN/Cloudflare server. They do not guarantee connectivity under network restrictions. [Detailed naming examples](docs/user/subscriptions.md#категории-профилей-по-названию).

## Included

- Android 8.0+ ARM64 APK, version 1.65.3.
- TUNA Routing Builder 2.4, a single-file Windows EXE and Python source.
- Per-app/domain/IP routing, subscription rules, URL-test groups and separate direct/VPN DNS pools (up to eight each).
- Source, native patch overlays, pinned dependencies, tests and developer documentation.

Bring your own servers/subscription. No private service credentials are distributed. The Windows editor is not a VPN client. Only trust subscriptions whose rules you accept: routing rules can apply automatically.

The compatibility APK is a non-debuggable release build, but retains the historical `app.tunaturn.android.debug` application ID for compatible updates. Android 13+ uses the production signing certificate with a verified rotation lineage; Android 8–12 retains the historical certificate. The EXE is not Authenticode-signed. See [release verification](docs/development/release.md) before installing. No fresh full-device acceptance or bit-for-bit reproducible build is implied by a successful compile.

Clone with `git clone --recurse-submodules https://github.com/lesovoi53/tuna-vpn.git`. GitHub's default source ZIP excludes submodule contents. Native source modifications live under `android/native/`; wrappers live under `android/external/`. Some native components are extracted from a checksum-pinned upstream APK; see the explicit provenance limitations.

Derived from WireTurn. TUNA patches do not claim authorship of upstream cores. Project code is GPL-3.0 unless a file declares another license; third-party licenses and credits are retained. [Core forks](docs/development/cores-and-forks.md) · [Security reporting](SECURITY.md).
