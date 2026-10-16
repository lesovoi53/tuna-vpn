# vless-client

A lightweight CLI proxy client built on [xray-core](https://github.com/xtls/xray-core). It parses a VLESS/Trojan/Hysteria2 link or a WireGuard config and exposes a local SOCKS5 and/or HTTP proxy — no manual JSON config required.

Created as a companion tool for [WireTurn](https://github.com/spkprsnts/WireTurn).

## Features

- **VLESS** proxy from a `vless://` link — supports TLS, REALITY, WebSocket, gRPC, TCP, XHTTP, mKCP
- **Trojan** proxy from a `trojan://` link — supports TLS, REALITY, and the same transports as VLESS
- **Hysteria2** proxy from a `hysteria2://` (or `hy2://`) link — QUIC-based, tuned for lossy/high-latency networks; supports Salamander obfuscation and Brutal congestion/bandwidth tuning
- **Mux multiplexing** to reduce DPI visibility of XHTTP connections (VLESS/Trojan only)
- **XHTTP anti-DPI obfuscation** — padding randomization, HTTP method override and session placement, configured via the link
- **WireGuard** tunnel — from a standard `.conf` file or individual CLI flags
- **Standalone SOCKS5** upstream proxy mode
- **Dual-route** mode with automatic failover and load balancing
- **VLESS/Trojan over SOCKS5** — chain the proxy link's own connection through a local SOCKS5 upstream (`-socks5-chain`)
- **Geosite/geoip routing** — bypass or block traffic by domain/IP category before it reaches the tunnel
- **ECH (Encrypted Client Hello)** for VLESS/Trojan/Hysteria2 over TLS — hides the real SNI from DPI, with automatic DNS-based config fetch
- Optional **YAML config file** (`-config`, default `config.yaml`) — CLI flags always override its values
- SOCKS5 proxy (always on)
- Optional HTTP proxy on a separate port
- Optional username/password auth on exposed SOCKS5 and HTTP proxies
- Authenticated upstream SOCKS5 (`user:pass@host:port`)
- Configurable DNS servers, including DoH/DoT/DoQ and local-vs-tunneled resolution
- **FakeDNS** — hand out synthetic IPs instead of resolving for real, for apps/protocols that need an IP before connecting
- Stats/status/health-check via an abstract Unix domain socket (`-stats-socket`, Linux/Android only) — see [Stats socket](#stats-socket)
- Debug logging via flag

## Installation

**Requirements:** Go 1.22+

```bash
git clone https://github.com/spkprsnts/vless-client
cd vless-client
go build -o vless-client .
```

## Usage

### VLESS — single route

```bash
./vless-client -link "vless://UUID@host:port?security=reality&..." -listen 127.0.0.1:1080
```

With anti-DPI obfuscation for XHTTP (when RKN/DPI blocks the connection), pass the settings via the `extra` query parameter in the VLESS link as a URL-encoded JSON object — the server admin includes these when sharing the link:

```bash
# extra={"xPaddingObfsMode":true,"xPaddingMethod":"tokenish","uplinkHTTPMethod":"PUT","sessionPlacement":"query"}
./vless-client \
  -link   "vless://UUID@host:port?type=xhttp&security=reality&...&extra=%7B%22xPaddingObfsMode%22%3Atrue%2C%22xPaddingMethod%22%3A%22tokenish%22%2C%22uplinkHTTPMethod%22%3A%22PUT%22%2C%22sessionPlacement%22%3A%22query%22%7D" \
  -listen 127.0.0.1:1080
```

With a local/CDN address override:

```bash
./vless-client \
  -link          "vless://UUID@host:port?security=reality&..." \
  -local-address 192.168.1.1:443 \
  -listen        127.0.0.1:1080
```

### ECH (Encrypted Client Hello)

Hides the real SNI inside the TLS handshake from on-path DPI. Works with VLESS, Trojan, and Hysteria2 whenever `security=tls` (not applicable to REALITY, which already hides the real destination a different way). Pass it via `echConfigList` in the link's query string:

```bash
# Raw ECHConfigList, base64-encoded (obtained out of band, e.g. from the server admin or `dig +short TYPE65 host.example.com`)
./vless-client -link "vless://UUID@host:443?security=tls&sni=host.example.com&echConfigList=<base64>" -listen 127.0.0.1:1080

# Or let Xray fetch it itself via DNS HTTPS record lookup at connect time:
./vless-client -link "vless://UUID@host:443?security=tls&sni=host.example.com&echConfigList=udp://1.1.1.1" -listen 127.0.0.1:1080
```

`echConfigList` accepts either:
- a raw base64-encoded ECHConfigList, or
- a DNS-query spec resolved automatically: `<dnsserver>` (queries the link's `sni` for an HTTPS/ECH record) or `<domain>+<dnsserver>` to query a different domain than `sni` — `dnsserver` can be `udp://ip[:port]`, `https://.../dns-query` (DoH), or `h2c://...`

Optional `echForceQuery=none|half|full` controls how aggressively the DNS-fetched config is re-queried instead of reused from cache (default: cached, refreshed in the background).

### More TLS/REALITY/TCP parameters

A few more link params for VLESS/Trojan, matching the short names Xray's own code recommends (and the same ones 3x-ui's share links use):

- `vcn=<name>` (`verifyPeerCertByName`) — verify the peer cert against a specific name instead of `sni`, for `security=tls`
- `pqv=<key>` (`mldsa65Verify`) — REALITY's post-quantum ML-DSA-65 verification key, for `security=reality`
- `headerType=http` on plain `type=tcp` — disguises the connection as plaintext HTTP (fake request line + headers), read from `path=`/`host=` (comma-separated for multiple, matching Xray's own `tcpSettings`)
- `fm=<url-encoded JSON>` — raw Xray `finalmask` passthrough, same as Hysteria2/mKCP's `fm=` below, but available on **any** transport (e.g. TCP fragment/sudoku masks)

```bash
./vless-client -link "vless://UUID@host:443?security=tls&sni=host.example.com&vcn=host.example.com" -listen 127.0.0.1:1080
./vless-client -link "vless://UUID@host:443?type=tcp&security=none&headerType=http&path=/&host=www.example.com" -listen 127.0.0.1:1080
```

### mKCP

A UDP-based transport that trades bandwidth efficiency for resilience on lossy links and, with a camouflage header, some protection against protocol fingerprinting. Works with VLESS/Trojan via `type=kcp` (or `mkcp`):

```bash
./vless-client -link "vless://UUID@host:443?security=none&type=kcp&headerType=wechat&seed=<password>" -listen 127.0.0.1:1080
```

- `mtu=`/`tti=`/`uplinkCapacity=`/`downlinkCapacity=`/`cwndMultiplier=`/`maxSendingWindow=` — optional numeric tuning, same meaning as Xray's `kcpSettings`; left at Xray's defaults if omitted
- `headerType=dns|dtls|srtp|utp|wechat|wireguard` — disguises packets as that protocol (the old mKCP "header type" camouflage, now implemented as a `finalmask` mask)
- `seed=<password>` — the closest replacement for mKCP's old seed-based obfuscation (mapped to the `mkcp-aes128gcm` finalmask mask); must match the server
- `fm=<url-encoded JSON>` — raw `finalmask` passthrough for anything beyond that (e.g. `header-custom`), same convention as Hysteria2's `fm=` above; takes priority over `headerType=`/`seed=`

mKCP traditionally runs with `security=none` and relies on `headerType=`/`seed=` camouflage instead of TLS (that's the whole point — looking like innocuous UDP traffic); leaving `security=tls` also works, just note the client defaults to `tls` when `security` isn't set at all, so pass `security=none` explicitly for the traditional setup.

### VLESS — dual route with load balancer

Connects through both a local/CDN address and the direct server address. Automatically uses whichever is reachable (lowest RTT).

```bash
./vless-client \
  -link           "vless://UUID@host:port?security=reality&..." \
  -local-address  192.168.1.1:443 \
  -direct-address server.example.com:443 \
  -listen         127.0.0.1:1080 \
  -stats-socket   vless-client
```

### Trojan

```bash
./vless-client -link "trojan://password@host:443?sni=host.example.com" -listen 127.0.0.1:1080
```

Same transport/TLS/REALITY query parameters as VLESS (`type`, `security`, `sni`, `fp`, `alpn`, `path`, `host`, `headers`, etc.), minus `flow`/`encryption` which are VLESS-only.

### Hysteria2

```bash
./vless-client -link "hysteria2://auth@host:443?sni=host.example.com" -listen 127.0.0.1:1080
```

`hy2://` is accepted as an alias for `hysteria2://`. Supports `auth`, `sni`, `alpn`, `pinnedPeerCertSha256`, `verifyPeerCertByName`, Salamander/Gecko obfuscation, UDP port hopping, and congestion/bandwidth tuning. `-mux` doesn't apply to Hysteria2 (QUIC already multiplexes streams).

Self-signed certs: since `allowInsecure` was removed upstream, pin the cert instead. `pinSHA256` (the original hysteria2 URI scheme's name for it, as used by some panels) is accepted as an alias for `pinnedPeerCertSha256`/`pcs`:

```bash
./vless-client -link "hysteria2://auth@host:443?pinnedPeerCertSha256=<hex-sha256>" -listen 127.0.0.1:1080
```

Salamander obfuscation (hides the QUIC handshake from DPI) and Brutal congestion control (bandwidth caps you declare so the client doesn't back off on packet loss the way TCP-style congestion control would):

```bash
./vless-client -link "hysteria2://auth@host:443?obfs=salamander&obfs-password=<pwd>&up=100&down=100" -listen 127.0.0.1:1080
```

- `obfs=salamander` + `obfs-password=<pwd>` (`obfs_password`/`obfsPassword` also accepted — some panels use those spellings) — must match the server's configuration
- `obfs=gecko` is also accepted as an alias for `salamander` (some panels emit it as a distinct obfuscation mode with an extra padding-size range); this build's Salamander implementation only has a password, so it behaves identically to plain `salamander` here — the padding-range tuning some links attach to it has nothing to bind to
- `up=`/`down=` — your own uplink/downlink caps in Mbps (a bare number), or a value with an explicit unit (e.g. `up=500kbps`); setting either enables Brutal congestion control by default
- `congestion=` — override the congestion algorithm explicitly (`brutal`, `bbr`, `reno`, or `force-brutal`, which requires `up`); defaults to `brutal` when `up`/`down` is set, otherwise left to Xray's default
- `mport=<port-or-range>` — enables UDP port hopping (e.g. `mport=20000-30000`)

For anything beyond that (receive-window tuning, etc.), pass the raw Xray `finalmask` block as JSON via `fm=` (URL-encoded) — the same escape hatch as XHTTP's `extra=`, and the same `fm=` convention used by panels like 3x-ui. `fm=` is authoritative for whichever sub-blocks (`udp`, `quicParams`) it defines, so it takes priority over `obfs=`/`up=`/`down=`/`congestion=`/`mport=`:

```bash
# fm={"quicParams":{"udpHop":{"ports":"20000-30000","interval":"5-10"}}}
./vless-client -link "hysteria2://auth@host:443?fm=%7B%22quicParams%22%3A%7B%22udpHop%22%3A%7B%22ports%22%3A%2220000-30000%22%2C%22interval%22%3A%225-10%22%7D%7D%7D" -listen 127.0.0.1:1080
```

`fm=` is authoritative for whichever sub-blocks (`udp`, `quicParams`) it defines; `obfs=`/`up=`/`down=`/`congestion=` only fill in the parts it leaves unset.

### VLESS — dual route with local SOCKS5 upstream

Connects through a local SOCKS5 proxy and the direct VLESS server. Automatically uses whichever is reachable.

```bash
./vless-client \
  -link           "vless://UUID@host:port?security=reality&..." \
  -local-socks5   127.0.0.1:1081 \
  -direct-address server.example.com:443 \
  -listen         127.0.0.1:1080 \
  -stats-socket   vless-client
```

### VLESS/Trojan over SOCKS5 (chain)

`-socks5-chain` changes what `-local-socks5` means: instead of being an alternate route (the dual-route mode above), it becomes the transport hop the proxy link's own connection is dialed through — VLESS/Trojan running *on top of* a local SOCKS5 upstream (e.g. Tor, another VPN's local proxy, or anything else already listening as a SOCKS5 proxy on this machine), rather than just using that SOCKS5 proxy directly. Internally this sets the outbound's `streamSettings.sockopt.dialerProxy` to a plain SOCKS5 outbound pointed at `-local-socks5` — the same pattern as [chaining VLESS through an upstream SOCKS5 in raw Xray config](https://github.com/spkprsnts/WireTurn/issues/15#issuecomment-5545513870).

```bash
./vless-client \
  -link          "vless://UUID@host:port?security=reality&..." \
  -local-socks5  127.0.0.1:9050 \
  -socks5-chain \
  -listen        127.0.0.1:1080
```

Not supported for Hysteria2 — its QUIC dialer manages its own UDP socket directly and doesn't go through Xray's generic dialer, so it can't be chained through a SOCKS5 hop this way.

Add `-direct-address` to get a dual-route version of chaining: both routes reach the **same** server, one connecting to it directly (preferred) and the other reaching it through `-local-socks5` (chained), automatically falling back to the chained route if the direct one is unreachable — useful when the direct path to the server gets blocked but the SOCKS5 upstream still has a way through:

```bash
./vless-client \
  -link           "vless://UUID@host:port?security=reality&..." \
  -local-socks5   127.0.0.1:9050 \
  -direct-address server.example.com:443 \
  -socks5-chain \
  -listen         127.0.0.1:1080
```

`-local-address` can't be combined with `-socks5-chain` + `-direct-address` (there's only one address in this mode — `-direct-address` — reached two ways), but still works normally with plain `-socks5-chain` alone, to override where the link itself points while still dialing through the SOCKS5 hop.

### Standalone SOCKS5 upstream

Use an existing SOCKS5 proxy as the upstream without any tunnel:

```bash
./vless-client \
  -local-socks5 127.0.0.1:1081 \
  -listen       127.0.0.1:1080
```

### WireGuard — from a config file

```bash
./vless-client -wg /etc/wireguard/wg0.conf -listen 127.0.0.1:1080
```

### WireGuard — from flags

```bash
./vless-client \
  -wg-private-key <base64-private-key> \
  -wg-public-key  <base64-public-key> \
  -wg-endpoint    vpn.example.com:51820 \
  -wg-address     10.0.0.2/32 \
  -listen         127.0.0.1:1080
```

### Authentication

Protect the exposed proxy with a username and password:

```bash
./vless-client \
  -link       "vless://UUID@host:port?security=reality&..." \
  -listen     127.0.0.1:1080 \
  -proxy-user alice \
  -proxy-pass secret
```

Both SOCKS5 and HTTP (`-http`) inbounds use the same credentials. Works with any mode (VLESS, WireGuard, standalone SOCKS5).

Connect through an upstream SOCKS5 that requires auth:

```bash
./vless-client \
  -local-socks5 alice:secret@127.0.0.1:1081 \
  -listen       127.0.0.1:1080
```

## YAML config file

Instead of (or alongside) flags, settings can come from a YAML file — see [config.example.yaml](config.example.yaml) for every available key. `config.yaml` in the working directory is loaded automatically if present; any flag passed explicitly on the command line overrides the corresponding value from the file.

```bash
cp config.example.yaml config.yaml
# edit config.yaml, then:
./vless-client
# or point to a different file:
./vless-client -config /path/to/other.yaml
```

## Geosite/geoip routing

`-route-direct` and `-route-block` (or `route_direct`/`route_block` in the YAML config) let you bypass or
drop traffic before it reaches the tunnel, using the same rule syntax as Xray's `routing.rules`. This works
in every mode (VLESS/Trojan/Hysteria2, WireGuard, and standalone SOCKS5).

Each is a comma-separated list of entries:

- `geosite:name` — a domain category from `geosite.dat` (e.g. `geosite:cn`, `geosite:category-ads-all`)
- `geoip:name` — an IP range category from `geoip.dat` (e.g. `geoip:cn`, `geoip:private`)
- a plain domain (e.g. `example.com`) or a CIDR (e.g. `10.0.0.0/8`)

`geosite:`/`geoip:` entries need the actual data files, downloaded separately (they're not bundled) —
e.g. from [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat/releases) or the
official [v2fly](https://github.com/v2fly) builds — placed in a directory pointed to by `-assets-path`
(or the `XRAY_LOCATION_ASSET` environment variable, which `-assets-path` sets for you). Plain domains/CIDRs
don't need any data file.

Block rules are checked before direct rules, so blocking a specific domain still works even if it also
falls under a category you're routing directly. When any `geoip:`/CIDR entry is used, `domainStrategy`
automatically switches to `IPIfNonMatch` so domains get resolved for IP matching; otherwise it stays `AsIs`.

```bash
./vless-client -link "vless://..." -listen 127.0.0.1:1080 \
  -assets-path /etc/xray/assets \
  -route-direct "geosite:cn,geoip:cn,geoip:private" \
  -route-block "geosite:category-ads-all"
```

## DNS

`-dns` (or `dns` in the YAML config) is a comma-separated list of servers used for the client's own domain
resolution (e.g. matching `geoip:`/CIDR routing rules, or dialing `-direct-address`/`-local-address` when
they're hostnames). Each entry can be a plain IP (classic UDP DNS) or a scheme:

| Scheme | Meaning |
|---|---|
| `tcp://host[:port]` | DNS over TCP, routed through the tunnel like any other traffic |
| `tcp+local://host[:port]` | DNS over TCP, resolved directly instead of through the tunnel |
| `https://host/path` | DNS-over-HTTPS (DoH), routed through the tunnel |
| `https+local://host/path` | DoH, resolved directly |
| `h2c://...` / `h2c+local://...` | Same as the two above, but plaintext HTTP/2 (h2c) instead of TLS |
| `quic+local://host[:port]` | DNS-over-QUIC (DoQ), resolved directly |
| `localhost` | Use the OS's own resolver |

"Routed through the tunnel" means the DNS query itself is dispatched like normal traffic (through your
routing rules, typically ending up in the proxy tunnel) — useful for hiding DNS queries from your ISP.
"`+local`" variants bypass the tunnel/routing and query directly from the machine running vless-client.

```bash
./vless-client -link "vless://..." -listen 127.0.0.1:1080 -dns "https://1.1.1.1/dns-query,tcp+local://8.8.8.8"
```

### FakeDNS

`-fakedns` (or `fakedns: true`) makes every DNS lookup return a synthetic IP instead of a real one — no
DNS query ever leaves the machine. When an app then connects to that fake IP, sniffing (already enabled on
both inbounds) recovers the real domain so routing — including `-route-direct`/`-route-block` geosite/geoip
rules — still applies as if the domain had been resolved normally.

This matters for apps or protocols that resolve a hostname to an IP before connecting (rather than handing
the domain itself to the SOCKS5/HTTP proxy, which already works fine without FakeDNS) — it saves a real DNS
round-trip and keeps the domain, not just an IP, visible to routing decisions in that case.

```bash
./vless-client -link "vless://..." -listen 127.0.0.1:1080 -fakedns
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `-listen` | *(required)* | SOCKS5 proxy listen address `ip:port` |
| `-link` | | Proxy link: `vless://`, `trojan://`, or `hysteria2://` (`hy2://`) |
| `-config` | `config.yaml` | Path to YAML config file (loaded if present; CLI flags override its values) |
| `-local-address` | | Override link destination `host:port` (local/CDN route) |
| `-direct-address` | | Direct server `host:port`; enables load balancing between local and direct routes |
| `-local-socks5` | | Local SOCKS5 proxy `[user:pass@]host:port`. Used as standalone upstream, as the local route when `-link` and `-direct-address` are also set, or as the transport hop when `-socks5-chain` is set |
| `-socks5-chain` | `false` | With `-link` and `-local-socks5`: dial the proxy link's own connection through `-local-socks5` instead of treating it as an alternate route. Add `-direct-address` for a dual-route version (same server, direct preferred, chained fallback). Not supported for hysteria2 |
| `-wg` | | Path to WireGuard `.conf` file |
| `-wg-private-key` | | WireGuard private key |
| `-wg-public-key` | | WireGuard peer public key |
| `-wg-preshared-key` | | WireGuard preshared key (optional) |
| `-wg-endpoint` | | WireGuard peer endpoint `host:port` |
| `-wg-address` | | WireGuard interface addresses, comma-separated |
| `-wg-mtu` | | WireGuard MTU (optional) |
| `-wg-keepalive` | | Persistent keepalive in seconds (optional) |
| `-http` | | Optional HTTP proxy address `ip:port` |
| `-dns` | `8.8.8.8,1.1.1.1` | Comma-separated DNS servers — see [DNS](#dns) for supported schemes |
| `-fakedns` | `false` | Hand out synthetic IPs instead of resolving for real; see [DNS](#dns) |
| `-stats-socket` | | Abstract Unix socket name for stats/status/check (Android/Linux only) |
| `-hc-interval` | `30` | Health check interval in seconds (dual-route mode) |
| `-hc-destination` | `http://connectivitycheck.gstatic.com/generate_204` | URL probed by the load balancer's health check (dual-route mode) |
| `-mux` | `0` | Enable Mux multiplexing with given concurrency (e.g. `8`); `0` disables. Incompatible with `flow=xtls-rprx-vision` |
| `-proxy-user` | | Username for the exposed SOCKS5/HTTP proxy |
| `-proxy-pass` | | Password for the exposed SOCKS5/HTTP proxy |
| `-assets-path` | | Directory containing `geoip.dat`/`geosite.dat` (sets `XRAY_LOCATION_ASSET`); required for `geosite:`/`geoip:` entries below |
| `-route-direct` | | Comma-separated match entries (`geosite:name`, `geoip:name`, plain domain, or CIDR) routed directly, bypassing the tunnel |
| `-route-block` | | Comma-separated match entries (same syntax) that are dropped entirely |
| `-debug` | `false` | Enable xray-core debug logging |

> **WireGuard config priority:** individual `-wg-*` flags override values from `-wg` config file.

## Stats socket

When `-stats-socket` is set, vless-client listens on a Linux abstract Unix domain socket. Only processes with the same UID can connect — other Android apps are rejected at the OS level via `SO_PEERCRED`.

```bash
./vless-client -link "vless://..." -listen 127.0.0.1:1080 -stats-socket vless-client
```

### Protocol

Send a single command line, receive a compact JSON response, connection closes.

**`stats`** — traffic counters

```json
{"tx_bytes":123456,"rx_bytes":654321}
```

**`status`** — outbound health (dual-route mode)

```json
{"outbounds":[{"tag":"local","alive":true,"ping":{"avg_ms":45,"min_ms":42,"max_ms":50,"total":10,"fail":0}},{"tag":"direct","alive":false,"ping":{"avg_ms":0,"min_ms":0,"max_ms":0,"total":10,"fail":10}}],"active":"local"}
```

`active` is the tag with the lowest RTT among alive outbounds, or `"none"` if both are unreachable.

**`check [n]`** — trigger manual health checks (dual-route mode, default 1 round, max 10)

```json
{"status":"check started","rounds":3}
```

### Android usage

```kotlin
val socket = LocalSocket()
socket.connect(LocalSocketAddress("vless-client", LocalSocketAddress.Namespace.ABSTRACT))
val response = socket.inputStream.bufferedReader().readLine()
socket.close()
// response: {"tx_bytes":123,"rx_bytes":456}
```

## License

MIT
