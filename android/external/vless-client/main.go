package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"

	"context"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/xtls/xray-core/app/observatory"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/extension"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Blank imports for xray-core features.
	// These are required to register the components with the core.
	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/observatory"
	_ "github.com/xtls/xray-core/app/observatory/burst"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	_ "github.com/xtls/xray-core/app/stats"
	_ "github.com/xtls/xray-core/proxy/blackhole"
	_ "github.com/xtls/xray-core/proxy/freedom"
	_ "github.com/xtls/xray-core/proxy/http"
	_ "github.com/xtls/xray-core/proxy/hysteria"
	_ "github.com/xtls/xray-core/proxy/socks"
	_ "github.com/xtls/xray-core/proxy/trojan"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/proxy/wireguard"
	_ "github.com/xtls/xray-core/transport/internet/grpc"
	_ "github.com/xtls/xray-core/transport/internet/httpupgrade"
	_ "github.com/xtls/xray-core/transport/internet/hysteria"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/splithttp"
	_ "github.com/xtls/xray-core/transport/internet/tagged/taggedimpl"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/websocket"

	_ "github.com/xtls/xray-core/app/dns"
	_ "github.com/xtls/xray-core/app/dns/fakedns"
	_ "github.com/xtls/xray-core/app/router"
)

// ProxyConfig holds a parsed proxy link. Protocol is "vless", "trojan", or "hysteria2".
// Credential is the UUID (vless), password (trojan), or auth string (hysteria2).
type ProxyConfig struct {
	Protocol   string
	Credential string
	Address    string
	Port       int
	Params     map[string]string
}

// WireGuard config structs
type WireGuardInterfaceConfig struct {
	PrivateKey string
	Address    []string
	MTU        int
}

type WireGuardPeerConfig struct {
	PublicKey    string
	PresharedKey string
	Endpoint     string
	KeepAlive    int
}

// FileConfig mirrors the CLI flags for use in an optional YAML config file.
// Any flag explicitly passed on the command line overrides the corresponding value here.
type FileConfig struct {
	RawXray        map[string]any `yaml:"raw_xray"`
	OutboundPatch  map[string]any `yaml:"outbound_patch"`
	Link           string         `yaml:"link"`
	WG             string         `yaml:"wg"`
	WGPrivateKey   string         `yaml:"wg_private_key"`
	WGPublicKey    string         `yaml:"wg_public_key"`
	WGPresharedKey string         `yaml:"wg_preshared_key"`
	WGEndpoint     string         `yaml:"wg_endpoint"`
	WGAddress      string         `yaml:"wg_address"`
	WGMTU          *int           `yaml:"wg_mtu"`
	WGKeepAlive    *int           `yaml:"wg_keepalive"`
	Listen         string         `yaml:"listen"`
	HTTP           string         `yaml:"http"`
	DNS            string         `yaml:"dns"`
	LocalAddress   string         `yaml:"local_address"`
	DirectAddress  string         `yaml:"direct_address"`
	LocalSocks5    string         `yaml:"local_socks5"`
	Socks5Chain    *bool          `yaml:"socks5_chain"`
	HCInterval     *int           `yaml:"hc_interval"`
	HCDestination  string         `yaml:"hc_destination"`
	Mux            *int           `yaml:"mux"`
	Debug          *bool          `yaml:"debug"`
	StatsSocket    string         `yaml:"stats_socket"`
	ProxyUser      string         `yaml:"proxy_user"`
	ProxyPass      string         `yaml:"proxy_pass"`
	AssetsPath     string         `yaml:"assets_path"`
	RouteDirect    string         `yaml:"route_direct"`
	RouteBlock     string         `yaml:"route_block"`
	FakeDNS        *bool          `yaml:"fakedns"`
}

// loadFileConfig reads and parses the YAML config at path. If the file is missing and
// explicit is false (i.e. the caller didn't ask for it via -config), that's not an error —
// it just means no config file is in use.
func loadFileConfig(path string, explicit bool) (*FileConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) && !explicit {
			return nil, nil
		}
		return nil, err
	}
	var cfg FileConfig
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parsing %s: %w", path, err)
	}
	return &cfg, nil
}

// applyFileConfig fills flag values from the file config, skipping any flag the user
// explicitly set on the command line (those always win).
func applyFileConfig(fc *FileConfig, setFlags map[string]bool, link, wgConfigPath, wgPrivateKey, wgPublicKey, wgPresharedKey, wgEndpoint, wgAddress, listen, httpSep, dnsServers, localAddress, directAddress, localSocks5, statsSocket, proxyUser, proxyPass, assetsPath, routeDirect, routeBlock, hcDestination *string, wgMTU, wgKeepAlive, hcInterval, muxConcurrency *int, debug, fakeDNS, socks5Chain *bool) {
	str := func(name string, dst *string, src string) {
		if !setFlags[name] && src != "" {
			*dst = src
		}
	}
	intp := func(name string, dst *int, src *int) {
		if !setFlags[name] && src != nil {
			*dst = *src
		}
	}
	boolp := func(name string, dst *bool, src *bool) {
		if !setFlags[name] && src != nil {
			*dst = *src
		}
	}

	str("link", link, fc.Link)
	str("wg", wgConfigPath, fc.WG)
	str("wg-private-key", wgPrivateKey, fc.WGPrivateKey)
	str("wg-public-key", wgPublicKey, fc.WGPublicKey)
	str("wg-preshared-key", wgPresharedKey, fc.WGPresharedKey)
	str("wg-endpoint", wgEndpoint, fc.WGEndpoint)
	str("wg-address", wgAddress, fc.WGAddress)
	intp("wg-mtu", wgMTU, fc.WGMTU)
	intp("wg-keepalive", wgKeepAlive, fc.WGKeepAlive)
	str("listen", listen, fc.Listen)
	str("http", httpSep, fc.HTTP)
	str("dns", dnsServers, fc.DNS)
	str("local-address", localAddress, fc.LocalAddress)
	str("direct-address", directAddress, fc.DirectAddress)
	str("local-socks5", localSocks5, fc.LocalSocks5)
	intp("hc-interval", hcInterval, fc.HCInterval)
	str("hc-destination", hcDestination, fc.HCDestination)
	intp("mux", muxConcurrency, fc.Mux)
	boolp("debug", debug, fc.Debug)
	str("stats-socket", statsSocket, fc.StatsSocket)
	str("proxy-user", proxyUser, fc.ProxyUser)
	str("proxy-pass", proxyPass, fc.ProxyPass)
	str("assets-path", assetsPath, fc.AssetsPath)
	str("route-direct", routeDirect, fc.RouteDirect)
	str("route-block", routeBlock, fc.RouteBlock)
	boolp("fakedns", fakeDNS, fc.FakeDNS)
	boolp("socks5-chain", socks5Chain, fc.Socks5Chain)
}

// Simple INI parser for WireGuard config
func parseWireGuardConfig(path string) (*WireGuardInterfaceConfig, *WireGuardPeerConfig, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, nil, err
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	iface := &WireGuardInterfaceConfig{}
	peer := &WireGuardPeerConfig{}
	inInterfaceSection := false
	inPeerSection := false

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		if strings.EqualFold(line, "[Interface]") {
			inInterfaceSection = true
			inPeerSection = false
			continue
		}

		if strings.EqualFold(line, "[Peer]") {
			inPeerSection = true
			inInterfaceSection = false
			continue
		}

		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(parts[0])
		value := strings.TrimSpace(parts[1])

		if inInterfaceSection {
			switch strings.ToLower(key) {
			case "privatekey":
				iface.PrivateKey = value
			case "address":
				for addr := range strings.SplitSeq(value, ",") {
					iface.Address = append(iface.Address, strings.TrimSpace(addr))
				}
			case "mtu":
				iface.MTU, _ = strconv.Atoi(value)
			}
		} else if inPeerSection {
			switch strings.ToLower(key) {
			case "publickey":
				peer.PublicKey = value
			case "presharedkey":
				peer.PresharedKey = value
			case "endpoint":
				peer.Endpoint = value
			case "persistentkeepalive":
				peer.KeepAlive, _ = strconv.Atoi(value)
			}
		}
	}

	if err := scanner.Err(); err != nil {
		return nil, nil, err
	}

	if iface.PrivateKey == "" || peer.PublicKey == "" || peer.Endpoint == "" {
		return nil, nil, fmt.Errorf("invalid wireguard config: missing PrivateKey, PublicKey, or Endpoint")
	}

	return iface, peer, nil
}

// buildInbounds - вспомогательная функция для генерации входящих соединений SOCKS/HTTP с поддержкой авторизации
func buildInbounds(listenAddr, httpAddr, authUser, authPass string, fakeDNS bool) []any {
	var inbounds []any
	listenHost, listenPortStr, _ := net.SplitHostPort(listenAddr)
	listenPort, _ := strconv.Atoi(listenPortStr)

	destOverride := []string{"http", "tls"}
	if fakeDNS {
		// Lets the dispatcher recover the real domain when a connection targets a
		// fake IP handed out by the "fakedns" DNS server, so domain-based routing
		// (including geosite rules) still works for apps that resolve via IP first.
		destOverride = append(destOverride, "fakedns")
	}

	socksSettings := map[string]any{"udp": true}
	if authUser != "" || authPass != "" {
		socksSettings["auth"] = "password"
		socksSettings["accounts"] = []any{
			map[string]any{"user": authUser, "pass": authPass},
		}
	}

	inbounds = append(inbounds, map[string]any{
		"listen":   listenHost,
		"port":     listenPort,
		"protocol": "socks",
		"settings": socksSettings,
		"sniffing": map[string]any{
			"enabled":      true,
			"destOverride": destOverride,
		},
	})

	if httpAddr != "" {
		httpHost, httpPortStr, _ := net.SplitHostPort(httpAddr)
		httpPort, _ := strconv.Atoi(httpPortStr)

		httpSettings := map[string]any{"timeout": 0}
		if authUser != "" || authPass != "" {
			httpSettings["accounts"] = []any{
				map[string]any{"user": authUser, "pass": authPass},
			}
		}

		inbounds = append(inbounds, map[string]any{
			"listen":   httpHost,
			"port":     httpPort,
			"protocol": "http",
			"settings": httpSettings,
			"sniffing": map[string]any{
				"enabled":      true,
				"destOverride": destOverride,
			},
		})
	}
	return inbounds
}

// withFakeDNS prepends the special "fakedns" nameserver, which always returns a synthetic IP
// for a domain instead of doing a real lookup; Xray auto-fills a matching IP pool for it, and
// the dispatcher recovers the real domain from that fake IP (via the "fakedns" destOverride
// buildInbounds adds) so domain-based routing keeps working for apps that resolve via IP first.
func withFakeDNS(dns []string, fakeDNS bool) []string {
	if !fakeDNS {
		return dns
	}
	return append([]string{"fakedns"}, dns...)
}

// Generate Xray configuration for WireGuard
func buildWireGuardXrayConfig(iface *WireGuardInterfaceConfig, peer *WireGuardPeerConfig, listenAddr, httpAddr string, dns []string, debug bool, authUser, authPass, routeDirect, routeBlock string, fakeDNS bool) ([]byte, error) {
	logLevel := "error"
	logAccess := "none"
	if debug {
		logLevel = "debug"
		logAccess = ""
	}

	// Inbounds
	inbounds := buildInbounds(listenAddr, httpAddr, authUser, authPass, fakeDNS)
	dns = withFakeDNS(dns, fakeDNS)

	peerConfig := map[string]any{
		"publicKey": peer.PublicKey,
		"endpoint":  peer.Endpoint,
	}
	if peer.PresharedKey != "" {
		peerConfig["preSharedKey"] = peer.PresharedKey
	}
	if peer.KeepAlive > 0 {
		peerConfig["keepAlive"] = peer.KeepAlive
	}

	wgSettings := map[string]any{
		"secretKey": iface.PrivateKey,
		"address":   iface.Address,
		"peers":     []any{peerConfig},
	}
	if iface.MTU > 0 {
		wgSettings["mtu"] = iface.MTU
	}

	outbounds := []any{
		map[string]any{
			"tag":      "proxy",
			"protocol": "wireguard",
			"settings": wgSettings,
		},
	}
	geoOutbounds, geoRules, domainStrategy := buildGeoRouting(routeDirect, routeBlock)
	outbounds = append(outbounds, geoOutbounds...)
	routingRules := append(geoRules, map[string]any{
		"type":        "field",
		"network":     "tcp,udp",
		"outboundTag": "proxy",
	})

	// Full configuration
	configJSON := map[string]any{
		"log":   map[string]any{"loglevel": logLevel, "access": logAccess},
		"stats": map[string]any{},
		"dns": map[string]any{
			"servers": dns,
		},
		"policy": map[string]any{
			"system": map[string]any{
				"statsOutboundUplink":   true,
				"statsOutboundDownlink": true,
			},
		},
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"routing": map[string]any{
			"domainStrategy": domainStrategy,
			"rules":          routingRules,
		},
	}
	return json.MarshalIndent(configJSON, "", "  ")
}

// parseProxyLink parses a vless://, trojan://, or hysteria2:// (hy2://) link. All four
// schemes share the same shape: scheme://credential@host:port[/][?params][#name].
func parseProxyLink(link string) (*ProxyConfig, error) {
	var protocol string
	switch {
	case strings.HasPrefix(link, "vless://"):
		protocol = "vless"
		link = strings.TrimPrefix(link, "vless://")
	case strings.HasPrefix(link, "trojan://"):
		protocol = "trojan"
		link = strings.TrimPrefix(link, "trojan://")
	case strings.HasPrefix(link, "hysteria2://"):
		protocol = "hysteria2"
		link = strings.TrimPrefix(link, "hysteria2://")
	case strings.HasPrefix(link, "hy2://"):
		protocol = "hysteria2"
		link = strings.TrimPrefix(link, "hy2://")
	default:
		return nil, fmt.Errorf("unsupported link scheme (expected vless://, trojan://, hysteria2://, or hy2://)")
	}

	parts := strings.SplitN(link, "@", 2)
	if len(parts) != 2 {
		return nil, fmt.Errorf("invalid %s link: missing '@'", protocol)
	}
	credential := parts[0]
	remaining := parts[1]

	hostPortAndParams := strings.SplitN(remaining, "?", 2)
	// hysteria2 links commonly include a trailing "/" before the query string.
	hostPort := strings.SplitN(hostPortAndParams[0], "/", 2)[0]
	host, portStr, err := net.SplitHostPort(hostPort)
	if err != nil {
		return nil, fmt.Errorf("invalid host:port: %v", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("invalid port: %v", err)
	}

	cfg := &ProxyConfig{
		Protocol:   protocol,
		Credential: credential,
		Address:    host,
		Port:       port,
		Params:     make(map[string]string),
	}

	if len(hostPortAndParams) > 1 {
		paramsPart := hostPortAndParams[1]
		paramsAndName := strings.SplitN(paramsPart, "#", 2)
		parsed, err := url.ParseQuery(paramsAndName[0])
		if err != nil {
			return nil, fmt.Errorf("invalid query params: %v", err)
		}
		for k, v := range parsed {
			if len(v) > 0 {
				cfg.Params[k] = v[0]
			}
		}
	}
	return cfg, nil
}

// paramAlias reads params[name], falling back to params[alias] if the primary key is absent.
// Used for link params where this client's naming (mirroring Xray's own JSON config field
// names) differs from the short names some panels — e.g. 3x-ui uses "ech"/"pcs" — put in
// their generated share links, so links copy-pasted from either source work unmodified.
func paramAlias(params map[string]string, name, alias string) string {
	if v := params[name]; v != "" {
		return v
	}
	return params[alias]
}

// firstParam returns the first non-empty value among the given keys, in order.
func firstParam(params map[string]string, keys ...string) string {
	for _, k := range keys {
		if v := params[k]; v != "" {
			return v
		}
	}
	return ""
}

// applyECHParams adds Encrypted Client Hello settings to a tlsSettings map from the
// echConfigList/echForceQuery link params ("ech" is accepted as an alias for echConfigList,
// matching 3x-ui's share links). echConfigList is either a raw base64-encoded ECHConfigList
// fetched out of band, or a DNS-query spec ("dnsserver" or "domain+dnsserver", e.g.
// "udp://1.1.1.1" or "cloudflare-ech.com+https://1.1.1.1/dns-query") that Xray resolves itself
// at connect time, querying the tlsSettings serverName if no domain is given.
func applyECHParams(tlsSettings map[string]any, params map[string]string) {
	v := paramAlias(params, "echConfigList", "ech")
	if v == "" {
		return
	}
	tlsSettings["echConfigList"] = v
	if fq := params["echForceQuery"]; fq != "" {
		tlsSettings["echForceQuery"] = fq
	}
}

// buildOutbound builds a single outbound map (vless or trojan) for use in Xray config.
// Hysteria2 is handled separately by buildHysteriaOutbound since it uses a dedicated
// QUIC-based transport instead of the ws/grpc/xhttp transports vless/trojan share.
func buildOutbound(cfg *ProxyConfig, tag string, muxConcurrency int) map[string]any {
	if cfg.Protocol == "hysteria2" {
		return buildHysteriaOutbound(cfg, tag)
	}

	security := cfg.Params["security"]
	if security == "" {
		security = "tls"
	}
	streamSettings := map[string]any{
		"network": cfg.Params["type"],
	}
	if cfg.Params["type"] == "" {
		streamSettings["network"] = "tcp"
	}

	sni := cfg.Params["sni"]
	if sni == "" {
		sni = cfg.Address
	}

	switch security {
	case "tls":
		streamSettings["security"] = "tls"
		tlsSettings := map[string]any{
			"serverName":  sni,
			"fingerprint": cfg.Params["fp"],
		}
		if alpn := cfg.Params["alpn"]; alpn != "" {
			tlsSettings["alpn"] = strings.Split(alpn, ",")
		}
		if v := paramAlias(cfg.Params, "pinnedPeerCertSha256", "pcs"); v != "" {
			tlsSettings["pinnedPeerCertSha256"] = v
		}
		if v := paramAlias(cfg.Params, "verifyPeerCertByName", "vcn"); v != "" {
			tlsSettings["verifyPeerCertByName"] = v
		}
		applyECHParams(tlsSettings, cfg.Params)
		streamSettings["tlsSettings"] = tlsSettings
	case "reality":
		streamSettings["security"] = "reality"
		realitySettings := map[string]any{
			"serverName":  sni,
			"fingerprint": cfg.Params["fp"],
			"publicKey":   cfg.Params["pbk"],
			"shortId":     cfg.Params["sid"],
		}
		if spx := cfg.Params["spx"]; spx != "" {
			realitySettings["spiderX"] = spx
		}
		// pqv is REALITY's post-quantum ML-DSA-65 verification key (3x-ui's param name).
		if v := paramAlias(cfg.Params, "mldsa65Verify", "pqv"); v != "" {
			realitySettings["mldsa65Verify"] = v
		}
		streamSettings["realitySettings"] = realitySettings
	}

	// parseHeaders decodes a JSON headers object from a URL param (e.g. headers={"X-Foo":"bar"}).
	parseHeaders := func(raw string) map[string]string {
		var h map[string]string
		_ = json.Unmarshal([]byte(raw), &h)
		return h
	}

	switch cfg.Params["type"] {
	case "ws":
		ws := map[string]any{}
		if v := cfg.Params["path"]; v != "" {
			ws["path"] = v
		}
		if v := cfg.Params["host"]; v != "" {
			ws["host"] = v
		}
		if v := cfg.Params["headers"]; v != "" {
			if h := parseHeaders(v); len(h) > 0 {
				ws["headers"] = h
			}
		}
		streamSettings["wsSettings"] = ws

	case "grpc":
		grpc := map[string]any{
			"serviceName": cfg.Params["serviceName"],
		}
		if v := cfg.Params["authority"]; v != "" {
			grpc["authority"] = v
		}
		// multiMode=1/true is this client's own naming; mode=multi is what 3x-ui puts
		// in its generated links, so both are accepted.
		if v := cfg.Params["multiMode"]; v == "1" || v == "true" || strings.EqualFold(cfg.Params["mode"], "multi") {
			grpc["multiMode"] = true
		}
		streamSettings["grpcSettings"] = grpc

	case "httpupgrade":
		hu := map[string]any{}
		if v := cfg.Params["path"]; v != "" {
			hu["path"] = v
		}
		if v := cfg.Params["host"]; v != "" {
			hu["host"] = v
		}
		if v := cfg.Params["headers"]; v != "" {
			if h := parseHeaders(v); len(h) > 0 {
				hu["headers"] = h
			}
		}
		streamSettings["httpupgradeSettings"] = hu

	case "xhttp", "splithttp":
		xhttp := map[string]any{}
		if v := cfg.Params["path"]; v != "" {
			xhttp["path"] = v
		}
		if v := cfg.Params["host"]; v != "" {
			xhttp["host"] = v
		}
		if v := cfg.Params["mode"]; v != "" {
			xhttp["mode"] = v
		}
		if v := cfg.Params["headers"]; v != "" {
			if h := parseHeaders(v); len(h) > 0 {
				xhttp["headers"] = h
			}
		}
		if extra := cfg.Params["extra"]; extra != "" {
			var extraMap map[string]any
			if err := json.Unmarshal([]byte(extra), &extraMap); err == nil {
				for k, v := range extraMap {
					xhttp[k] = v
				}
			}
		}
		// Xray 26.9 uses sessionID* names; keep older exported links working.
		for old, current := range map[string]string{"sessionPlacement": "sessionIDPlacement", "sessionKey": "sessionIDKey"} {
			if value, exists := xhttp[old]; exists {
				if _, explicit := xhttp[current]; !explicit {
					xhttp[current] = value
				}
				delete(xhttp, old)
			}
		}
		streamSettings["xhttpSettings"] = xhttp

	case "kcp", "mkcp":
		kcp := map[string]any{}
		setUintParam(kcp, "mtu", cfg.Params["mtu"])
		setUintParam(kcp, "tti", cfg.Params["tti"])
		setUintParam(kcp, "uplinkCapacity", cfg.Params["uplinkCapacity"])
		setUintParam(kcp, "downlinkCapacity", cfg.Params["downlinkCapacity"])
		setUintParam(kcp, "cwndMultiplier", cfg.Params["cwndMultiplier"])
		setUintParam(kcp, "maxSendingWindow", cfg.Params["maxSendingWindow"])
		streamSettings["kcpSettings"] = kcp
		if fm := buildKcpFinalMask(cfg.Params); fm != nil {
			streamSettings["finalmask"] = fm
		}

	case "tcp", "":
		// headerType=http disguises the raw TCP stream as plaintext HTTP (fake request
		// line + headers) — the classic TCP camouflage mode, as generated e.g. by 3x-ui.
		if strings.EqualFold(cfg.Params["headerType"], "http") {
			path := cfg.Params["path"]
			if path == "" {
				path = "/"
			}
			request := map[string]any{
				"version": "1.1",
				"method":  "GET",
				"path":    strings.Split(path, ","),
			}
			if host := cfg.Params["host"]; host != "" {
				request["headers"] = map[string]any{"Host": strings.Split(host, ",")}
			}
			streamSettings["tcpSettings"] = map[string]any{
				"header": map[string]any{
					"type":    "http",
					"request": request,
				},
			}
		}
	}

	// fm=<url-encoded JSON> is a raw Xray "finalmask" passthrough available on any transport
	// (TCP fragment/sudoku/header-custom masks included), not just Hysteria2/mKCP — matches
	// 3x-ui, which attaches it to every stream type. Skipped if the network-specific case
	// above (mKCP) already built its own finalmask from fm=/obfs=/etc.
	if _, exists := streamSettings["finalmask"]; !exists {
		if fm := parseFinalMaskJSON(cfg.Params["fm"]); fm != nil {
			streamSettings["finalmask"] = fm
		}
	}

	var settings map[string]any
	if cfg.Protocol == "trojan" {
		settings = map[string]any{
			"servers": []any{
				map[string]any{
					"address":  cfg.Address,
					"port":     cfg.Port,
					"password": cfg.Credential,
				},
			},
		}
	} else {
		// Xray's VLESS outbound rejects the whole config at startup if "encryption" isn't
		// exactly "none" (or a valid post-quantum ML-KEM string), so default it here rather
		// than let a link that omits the param (in practice, essentially none do) crash the
		// whole client at boot.
		encryption := cfg.Params["encryption"]
		if encryption == "" {
			encryption = "none"
		}
		settings = map[string]any{
			"vnext": []any{
				map[string]any{
					"address": cfg.Address,
					"port":    cfg.Port,
					"users": []any{
						map[string]any{
							"id":         cfg.Credential,
							"encryption": encryption,
							"flow":       cfg.Params["flow"],
						},
					},
				},
			},
		}
	}

	out := map[string]any{
		"tag":            tag,
		"protocol":       cfg.Protocol,
		"settings":       settings,
		"streamSettings": streamSettings,
	}
	if muxConcurrency > 0 {
		out["mux"] = map[string]any{
			"enabled":     true,
			"concurrency": muxConcurrency,
		}
	}
	return out
}

// buildHysteriaOutbound builds a Hysteria2 outbound. Unlike vless/trojan it doesn't use the
// ws/grpc/xhttp transports — Hysteria2 is a self-contained QUIC protocol (network "hysteria")
// that always runs over TLS. Supports auth, SNI/ALPN/cert pinning, Salamander obfuscation
// (obfs=salamander&obfs-password=...), and congestion/bandwidth tuning (up=/down=/congestion=).
func buildHysteriaOutbound(cfg *ProxyConfig, tag string) map[string]any {
	sni := cfg.Params["sni"]
	if sni == "" {
		sni = cfg.Address
	}

	tlsSettings := map[string]any{
		"serverName": sni,
	}
	if alpn := cfg.Params["alpn"]; alpn != "" {
		tlsSettings["alpn"] = strings.Split(alpn, ",")
	}
	// Hysteria2 links commonly carry the pin as "pinSHA256" (the original hysteria2 URI
	// scheme's name for it) rather than "pcs" like other protocols' 3x-ui-generated links.
	if v := firstParam(cfg.Params, "pinnedPeerCertSha256", "pcs", "pinSHA256"); v != "" {
		tlsSettings["pinnedPeerCertSha256"] = v
	}
	if v := paramAlias(cfg.Params, "verifyPeerCertByName", "vcn"); v != "" {
		tlsSettings["verifyPeerCertByName"] = v
	}
	applyECHParams(tlsSettings, cfg.Params)

	streamSettings := map[string]any{
		"network":     "hysteria",
		"security":    "tls",
		"tlsSettings": tlsSettings,
		"hysteriaSettings": map[string]any{
			"version": 2,
			"auth":    cfg.Credential,
		},
	}
	if fm := buildHysteriaFinalMask(cfg.Params); fm != nil {
		streamSettings["finalmask"] = fm
	}

	return map[string]any{
		"tag":      tag,
		"protocol": "hysteria",
		"settings": map[string]any{
			"version": 2,
			"address": cfg.Address,
			"port":    cfg.Port,
		},
		"streamSettings": streamSettings,
	}
}

// buildHysteriaFinalMask builds the optional Hysteria2 "finalmask" stream-settings block from
// link params: obfs/obfs-password (Salamander UDP obfuscation) and up/down/congestion (QUIC
// congestion control). Returns nil if none of these params are set, leaving Xray's defaults.
func buildHysteriaFinalMask(params map[string]string) map[string]any {
	// fm=<url-encoded JSON> is a raw passthrough for the whole finalmask block — the same
	// escape hatch xhttp's "extra" param provides, but for Hysteria2/QUIC tuning (matches
	// the convention used by panels like 3x-ui for anything beyond basic Salamander/bandwidth:
	// gecko obfuscation, UDP port hopping, receive windows, etc.). Whatever it defines is
	// treated as authoritative; the simpler obfs=/up=/down=/congestion= params below only
	// fill in the "udp"/"quicParams" sub-blocks it left unset.
	fm := parseFinalMaskJSON(params["fm"])

	// "gecko" (as generated by e.g. 3x-ui) is Salamander with an extra packetSize padding
	// range; this xray-core build's salamander mask only has a "password" field (no
	// packetSize), so gecko is mapped to plain salamander here — same obfuscation/password,
	// just without the padding-range tuning, which this engine build has no way to apply.
	obfs := strings.ToLower(params["obfs"])
	if obfs == "salamander" || obfs == "gecko" {
		if fm == nil {
			fm = map[string]any{}
		}
		if _, exists := fm["udp"]; !exists {
			password := firstParam(params, "obfs-password", "obfs_password", "obfsPassword")
			fm["udp"] = []any{
				map[string]any{
					"type":     "salamander",
					"settings": map[string]any{"password": password},
				},
			}
		}
	}

	up, down, congestion, mport := params["up"], params["down"], params["congestion"], params["mport"]
	if up != "" || down != "" || congestion != "" {
		if fm == nil {
			fm = map[string]any{}
		}
		if _, exists := fm["quicParams"]; !exists {
			quicParams := map[string]any{}
			if up != "" || down != "" || congestion != "" {
				if congestion != "" {
					quicParams["congestion"] = strings.ToLower(congestion)
				} else {
					// Hysteria2's whole design point is client-declared bandwidth caps driving
					// Brutal congestion control, so default to "brutal" whenever up/down is set.
					quicParams["congestion"] = "brutal"
				}
				if up != "" {
					quicParams["brutalUp"] = normalizeBandwidth(up)
				}
				if down != "" {
					quicParams["brutalDown"] = normalizeBandwidth(down)
				}
			}
			fm["quicParams"] = quicParams
		}
	}

	if mport != "" {
		if fm == nil {
			fm = map[string]any{}
		}
		masks, _ := fm["udp"].([]any)
		hasHop := false
		for _, entry := range masks {
			if mask, ok := entry.(map[string]any); ok && mask["type"] == "udphop" {
				hasHop = true
			}
		}
		if !hasHop {
			hop := map[string]any{"type": "udphop", "settings": map[string]any{"mode": "intervalRemote", "remotePorts": mport, "interval": "5-10"}}
			fm["udp"] = append([]any{hop}, masks...)
		}
	}
	return fm
}

// normalizeBandwidth accepts a bare number (interpreted as Mbps, matching the convention used
// by hysteria2:// up=/down= params in the wild) or a value with an explicit unit (e.g. "500kbps")
// and returns a string in the form Xray's Bandwidth type parses.
func normalizeBandwidth(v string) string {
	v = strings.TrimSpace(v)
	if _, err := strconv.ParseFloat(v, 64); err == nil {
		return v + "mbps"
	}
	return v
}

// setUintParam parses raw as an unsigned integer and sets m[key] if it's valid, leaving m
// untouched (so Xray falls back to its own default) when raw is empty or unparseable.
func setUintParam(m map[string]any, key, raw string) {
	if raw == "" {
		return
	}
	if v, err := strconv.ParseUint(raw, 10, 32); err == nil {
		m[key] = v
	}
}

// buildKcpFinalMask builds the optional mKCP "finalmask" stream-settings block from link
// params. mKCP's old built-in header camouflage and seed-based obfuscation were removed
// upstream in favor of finalmask masks, so:
//   - headerType=dns|dtls|srtp|utp|wechat|wireguard picks a UDP camouflage mask that makes
//     the traffic look like that protocol (matches the old mKCP "header type" convention)
//   - seed=<password> is the closest replacement for the old seed-based obfuscation, mapped
//     to the "mkcp-aes128gcm" mask
//
// As with Hysteria2's finalmask, fm=<url-encoded JSON> is a raw passthrough that takes
// priority over both — it's authoritative for whichever sub-blocks it defines.
func buildKcpFinalMask(params map[string]string) map[string]any {
	fm := parseFinalMaskJSON(params["fm"])

	if ht := strings.ToLower(params["headerType"]); ht != "" && ht != "none" {
		switch ht {
		case "dns", "dtls", "srtp", "utp", "wechat", "wireguard":
			if fm == nil {
				fm = map[string]any{}
			}
			if _, exists := fm["udp"]; !exists {
				fm["udp"] = []any{map[string]any{"type": "header-" + ht, "settings": map[string]any{}}}
			}
		}
	}

	if seed := params["seed"]; seed != "" {
		if fm == nil {
			fm = map[string]any{}
		}
		if _, exists := fm["udp"]; !exists {
			fm["udp"] = []any{
				map[string]any{
					"type":     "mkcp-aes128gcm",
					"settings": map[string]any{"password": seed},
				},
			}
		}
	}

	return fm
}

// parseRouteSpec splits a comma-separated routing match spec (e.g. "geosite:cn,geoip:cn,geoip:private")
// into the "domain" and "ip" arrays used by an Xray field routing rule. "geoip:" entries and literal
// IPs/CIDRs go to ip[]; everything else (geosite:, domain:, full:, regexp:, keyword:, or a bare domain)
// goes to domain[]. Requires geoip.dat/geosite.dat to be reachable via -assets-path (XRAY_LOCATION_ASSET).
func parseRouteSpec(spec string) (domains []string, ips []string) {
	for entry := range strings.SplitSeq(spec, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		if strings.HasPrefix(entry, "geoip:") {
			ips = append(ips, entry)
			continue
		}
		if _, _, err := net.ParseCIDR(entry); err == nil {
			ips = append(ips, entry)
			continue
		}
		if net.ParseIP(entry) != nil {
			ips = append(ips, entry)
			continue
		}
		domains = append(domains, entry)
	}
	return domains, ips
}

// buildGeoRouting builds the optional freedom ("bypass-out") and blackhole ("block-out") outbounds
// plus their routing rules for -route-direct/-route-block. Block rules are returned before direct
// rules so a blocked entry wins even when it also falls under a broader directly-routed category.
// The returned domainStrategy is "IPIfNonMatch" if any "ip" rule was produced (domains are only
// resolved to IP for geoip matching when that's needed), otherwise "AsIs".
//
// The freedom outbound is deliberately NOT tagged "direct-out": Xray's balancer selector
// matching is prefix-based (strings.HasPrefix, see app/proxyman/outbound.Manager.Select), so a
// dual-route balancer selecting on "direct" (main.go's "direct" outbound tag) would also match
// "direct-out" and fold this unencrypted, untunneled freedom outbound into its candidate pool.
// Since a raw connection almost always pings faster than one through the tunnel, leastping would
// then route ALL general traffic (not just -route-direct's own explicit rule) through it —
// silently bypassing the tunnel entirely. "bypass-out" shares no prefix with "direct"/"local".
func buildGeoRouting(routeDirect, routeBlock string) (outbounds []any, rules []any, domainStrategy string) {
	domainStrategy = "AsIs"
	add := func(spec, tag, protocol string) {
		if spec == "" {
			return
		}
		domains, ips := parseRouteSpec(spec)
		if len(domains) == 0 && len(ips) == 0 {
			return
		}
		rule := map[string]any{"type": "field", "outboundTag": tag}
		if len(domains) > 0 {
			rule["domain"] = domains
		}
		if len(ips) > 0 {
			rule["ip"] = ips
			// Domains only need resolving to check them against an "ip" rule, so only
			// switch away from the AsIs default when a rule actually has one.
			domainStrategy = "IPIfNonMatch"
		}
		rules = append(rules, rule)
		outbounds = append(outbounds, map[string]any{"tag": tag, "protocol": protocol, "settings": map[string]any{}})
	}
	add(routeBlock, "block-out", "blackhole")
	add(routeDirect, "bypass-out", "freedom")
	return outbounds, rules, domainStrategy
}

// parseFinalMaskJSON decodes a raw fm=<url-encoded JSON> value (Xray's "finalmask" block,
// passed through verbatim) into a generic map, or returns nil if raw is empty or invalid.
func parseFinalMaskJSON(raw string) map[string]any {
	if raw == "" {
		return nil
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		return nil
	}
	return parsed
}

// buildSocks5Outbound builds a plain SOCKS5 outbound (no protocol wrapping) pointed at
// localSocks5, used both as an alternate route in the load-balancer mode and as the
// underlying dial target in -socks5-chain mode.
func buildSocks5Outbound(tag, localSocks5, localSocks5User, localSocks5Pass string) map[string]any {
	host, portStr, _ := net.SplitHostPort(localSocks5)
	port, _ := strconv.Atoi(portStr)
	serverSettings := map[string]any{"address": host, "port": port}
	if localSocks5User != "" || localSocks5Pass != "" {
		serverSettings["users"] = []any{
			map[string]any{"user": localSocks5User, "pass": localSocks5Pass},
		}
	}
	return map[string]any{
		"tag":      tag,
		"protocol": "socks",
		"settings": map[string]any{
			"servers": []any{serverSettings},
			"version": "5",
		},
	}
}

// setDialerProxy points an outbound's streamSettings.sockopt.dialerProxy at tag, so its
// underlying connection is dialed through that other tagged outbound instead of directly.
func setDialerProxy(outbound map[string]any, tag string) {
	streamSettings, _ := outbound["streamSettings"].(map[string]any)
	if streamSettings == nil {
		streamSettings = map[string]any{}
		outbound["streamSettings"] = streamSettings
	}
	streamSettings["sockopt"] = map[string]any{"dialerProxy": tag}
}

// Generate Xray configuration. When len(cfgs) > 1, enables load balancing with health checks.
func buildXrayConfig(cfgs []*ProxyConfig, localSocks5, localSocks5User, localSocks5Pass, listenAddr, httpAddr string, dns []string, debug bool, hcInterval, muxConcurrency int, authUser, authPass, routeDirect, routeBlock, hcDestination string, fakeDNS, socks5Chain, chainDualRoute bool) ([]byte, error) {
	logLevel := "error"
	logAccess := "none"
	if debug {
		logLevel = "debug"
		logAccess = ""
	}

	// Inbounds
	inbounds := buildInbounds(listenAddr, httpAddr, authUser, authPass, fakeDNS)

	// Outbounds
	var outbounds []any
	var tags []string
	if socks5Chain && chainDualRoute {
		// Same destination (cfgs[0]), two dial paths: "direct" connects straight to it and is
		// preferred by the balancer below; "local" is the same proxy config but chained through
		// localSocks5 via dialerProxy, used as a fallback if the direct path is unreachable.
		tags = []string{"local", "direct"}
		localOutbound := buildOutbound(cfgs[0], "local", muxConcurrency)
		setDialerProxy(localOutbound, "socks5-chain-out")
		outbounds = append(outbounds,
			localOutbound,
			buildOutbound(cfgs[0], "direct", muxConcurrency),
			buildSocks5Outbound("socks5-chain-out", localSocks5, localSocks5User, localSocks5Pass),
		)
	} else if socks5Chain {
		// VLESS/Trojan-over-SOCKS5: the proxy protocol itself dials through localSocks5
		// (via streamSettings.sockopt.dialerProxy) instead of treating it as an alternate
		// route, so a local upstream (Tor, another VPN's local proxy, etc.) becomes the
		// transport hop the real proxy server is reached through.
		proxyOutbound := buildOutbound(cfgs[0], "proxy", muxConcurrency)
		setDialerProxy(proxyOutbound, "socks5-chain-out")
		outbounds = []any{proxyOutbound, buildSocks5Outbound("socks5-chain-out", localSocks5, localSocks5User, localSocks5Pass)}
	} else if localSocks5 != "" {
		tags = []string{"local", "direct"}
		outbounds = append(outbounds, buildSocks5Outbound("local", localSocks5, localSocks5User, localSocks5Pass))
		// cfgs[0] is the direct proxy config
		outbounds = append(outbounds, buildOutbound(cfgs[0], "direct", muxConcurrency))
	} else if len(cfgs) == 1 {
		outbounds = []any{buildOutbound(cfgs[0], "proxy", muxConcurrency)}
	} else {
		tags = []string{"local", "direct"}
		for i, cfg := range cfgs {
			outbounds = append(outbounds, buildOutbound(cfg, tags[i], muxConcurrency))
		}
	}

	var finalDNS = dns
	if localSocks5 != "" && !socks5Chain {
		var tcpDNS []string
		for _, d := range dns {
			tcpDNS = append(tcpDNS, "tcp://"+d)
		}
		finalDNS = tcpDNS
		outbounds = append(outbounds, map[string]any{"tag": "dns-out", "protocol": "dns"})
	}
	finalDNS = withFakeDNS(finalDNS, fakeDNS)

	geoOutbounds, geoRules, domainStrategy := buildGeoRouting(routeDirect, routeBlock)
	outbounds = append(outbounds, geoOutbounds...)

	configJSON := map[string]any{
		"log":   map[string]any{"loglevel": logLevel, "access": logAccess},
		"stats": map[string]any{},
		"dns": map[string]any{
			"servers": finalDNS,
		},
		"policy": map[string]any{
			"system": map[string]any{
				"statsOutboundUplink":   true,
				"statsOutboundDownlink": true,
			},
		},
		"inbounds":  inbounds,
		"outbounds": outbounds,
	}

	var routingRules []any
	if localSocks5 != "" && !socks5Chain {
		routingRules = append(routingRules, map[string]any{
			"type":        "field",
			"network":     "udp",
			"port":        53,
			"outboundTag": "dns-out",
		})
	}
	routingRules = append(routingRules, geoRules...)

	// Add load balancer with health-check-based selection when two configs are provided.
	if len(tags) > 1 {
		configJSON["burstObservatory"] = map[string]any{
			"subjectSelector": tags,
			"pingConfig": map[string]any{
				"destination": hcDestination,
				"interval":    fmt.Sprintf("%ds", hcInterval),
				"sampling":    3,
				"timeout":     "5s",
			},
		}
		configJSON["routing"] = map[string]any{
			"domainStrategy": domainStrategy,
			"balancers": []any{
				map[string]any{
					"tag":         "balancer",
					"selector":    []string{"direct"},
					"strategy":    map[string]any{"type": "leastping"},
					"fallbackTag": "local",
				},
			},
		}
		routingRules = append(routingRules, map[string]any{
			"type":        "field",
			"network":     "tcp,udp",
			"balancerTag": "balancer",
		})
	} else {
		routingRules = append(routingRules, map[string]any{
			"type":        "field",
			"network":     "tcp,udp",
			"outboundTag": "proxy",
		})
	}

	if r, ok := configJSON["routing"].(map[string]any); ok {
		r["rules"] = routingRules
	} else {
		configJSON["routing"] = map[string]any{
			"domainStrategy": domainStrategy,
			"rules":          routingRules,
		}
	}

	return json.MarshalIndent(configJSON, "", "  ")
}

// Generate Xray configuration for a standalone SOCKS5 upstream proxy
func buildSocks5XrayConfig(localSocks5, localSocks5User, localSocks5Pass, listenAddr, httpAddr string, dns []string, debug bool, authUser, authPass, routeDirect, routeBlock string, fakeDNS bool) ([]byte, error) {
	logLevel := "error"
	logAccess := "none"
	if debug {
		logLevel = "debug"
		logAccess = ""
	}

	// Inbounds
	inbounds := buildInbounds(listenAddr, httpAddr, authUser, authPass, fakeDNS)

	// Convert standard DNS IPs to TCP DNS to bypass UDP limitations
	var tcpDNS []string
	for _, d := range dns {
		tcpDNS = append(tcpDNS, "tcp://"+d)
	}
	tcpDNS = withFakeDNS(tcpDNS, fakeDNS)

	outbounds := []any{
		buildSocks5Outbound("proxy", localSocks5, localSocks5User, localSocks5Pass),
		map[string]any{"tag": "dns-out", "protocol": "dns"},
	}
	geoOutbounds, geoRules, domainStrategy := buildGeoRouting(routeDirect, routeBlock)
	outbounds = append(outbounds, geoOutbounds...)
	routingRules := []any{
		map[string]any{"type": "field", "network": "udp", "port": 53, "outboundTag": "dns-out"},
	}
	routingRules = append(routingRules, geoRules...)
	routingRules = append(routingRules, map[string]any{"type": "field", "network": "tcp,udp", "outboundTag": "proxy"})

	configJSON := map[string]any{
		"log":   map[string]any{"loglevel": logLevel, "access": logAccess},
		"stats": map[string]any{},
		"dns":   map[string]any{"servers": tcpDNS},
		"policy": map[string]any{
			"system": map[string]any{"statsOutboundUplink": true, "statsOutboundDownlink": true},
		},
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"routing": map[string]any{
			"domainStrategy": domainStrategy,
			"rules":          routingRules,
		},
	}
	return json.MarshalIndent(configJSON, "", "  ")
}

// parseHostPort splits addr into a host and numeric port, exiting with a fatal error that
// names flagName if addr isn't a valid "host:port" or the port isn't numeric.
func parseHostPort(flagName, addr string) (string, int) {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		log.Fatalf("Invalid %s %q: %v", flagName, addr, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		log.Fatalf("Invalid port in %s %q: %v", flagName, addr, err)
	}
	return host, port
}

func main() {
	checkConfig := flag.Bool("check-config", false, "Validate without starting listeners")
	dumpConfig := flag.Bool("dump-config", false, "Print final private configuration; contains credentials")
	link := flag.String("link", "", "Proxy link: vless://, trojan://, or hysteria2:// (hy2://)")
	wgConfigPath := flag.String("wg", "", "Path to WireGuard .conf file (overridden by wg-* flags)")
	wgPrivateKey := flag.String("wg-private-key", "", "WireGuard private key")
	wgPublicKey := flag.String("wg-public-key", "", "WireGuard peer public key")
	wgPresharedKey := flag.String("wg-preshared-key", "", "WireGuard preshared key (optional)")
	wgEndpoint := flag.String("wg-endpoint", "", "WireGuard peer endpoint host:port")
	wgAddress := flag.String("wg-address", "", "WireGuard interface addresses, comma-separated (e.g. 10.0.0.2/32)")
	wgMTU := flag.Int("wg-mtu", 0, "WireGuard MTU (optional)")
	wgKeepAlive := flag.Int("wg-keepalive", 0, "WireGuard persistent keepalive in seconds (optional)")
	listen := flag.String("listen", "", "SOCKS5 proxy listen address ip:port (required)")
	httpSep := flag.String("http", "", "HTTP proxy listen address ip:port (optional)")
	dnsServers := flag.String("dns", "8.8.8.8,1.1.1.1", "Comma-separated DNS servers")
	localAddress := flag.String("local-address", "", "Override proxy destination to this host:port (local/CDN route)")
	directAddress := flag.String("direct-address", "", "Direct server host:port; enables load balancing between local and direct routes")
	localSocks5 := flag.String("local-socks5", "", "Local SOCKS5 proxy ([user:pass@]host:port). Used as standalone upstream, or instead of the local route if -link and -direct-address are set")
	socks5Chain := flag.Bool("socks5-chain", false, "With -link and -local-socks5: dial the proxy link's own connection through -local-socks5 (VLESS/Trojan-over-SOCKS5) instead of treating it as an alternate route. Add -direct-address for a dual-route version (same server, direct preferred, chained fallback). Not supported for hysteria2")
	hcInterval := flag.Int("hc-interval", 30, "Load balancer health check interval in seconds")
	hcDestination := flag.String("hc-destination", "http://connectivitycheck.gstatic.com/generate_204", "URL probed by the load balancer's health check")
	muxConcurrency := flag.Int("mux", 0, "Enable Mux multiplexing with given concurrency (e.g. 8); 0 disables")
	debug := flag.Bool("debug", false, "Enable xray-core debug logging")
	statsSocket := flag.String("stats-socket", "", "Abstract Unix socket name for stats/status/check (Android/Linux only, e.g. vless-client)")
	proxyUser := flag.String("proxy-user", "", "SOCKS5/HTTP proxy username (optional)")
	proxyPass := flag.String("proxy-pass", "", "SOCKS5/HTTP proxy password (optional)")
	assetsPath := flag.String("assets-path", "", "Directory containing geoip.dat/geosite.dat, required for geosite:/geoip: entries in -route-direct/-route-block (sets XRAY_LOCATION_ASSET)")
	routeDirect := flag.String("route-direct", "", "Comma-separated match entries (geosite:name, geoip:name, plain domain, or CIDR) routed directly, bypassing the tunnel")
	routeBlock := flag.String("route-block", "", "Comma-separated match entries (geosite:name, geoip:name, plain domain, or CIDR) that are blocked entirely")
	fakeDNS := flag.Bool("fakedns", false, "Serve synthetic IPs for DNS lookups instead of resolving for real, recovering the domain via sniffing for routing (useful for apps/protocols that resolve via IP before connecting)")
	configPath := flag.String("config", "config.yaml", "Path to YAML config file (loaded if present; explicit CLI flags override its values)")
	flag.Parse()

	setFlags := map[string]bool{}
	flag.Visit(func(f *flag.Flag) { setFlags[f.Name] = true })

	fileCfg, err := loadFileConfig(*configPath, setFlags["config"])
	if err != nil {
		log.Fatalf("Failed to load config file %s: %v", *configPath, err)
	}
	if fileCfg != nil {
		applyFileConfig(fileCfg, setFlags,
			link, wgConfigPath, wgPrivateKey, wgPublicKey, wgPresharedKey, wgEndpoint, wgAddress,
			listen, httpSep, dnsServers, localAddress, directAddress, localSocks5, statsSocket, proxyUser, proxyPass,
			assetsPath, routeDirect, routeBlock, hcDestination,
			wgMTU, wgKeepAlive, hcInterval, muxConcurrency, debug, fakeDNS, socks5Chain)
		log.Printf("Loaded config file %s", *configPath)
	}

	if *listen == "" {
		log.Fatal("-listen is required")
	}

	if *assetsPath != "" {
		if err := os.Setenv("XRAY_LOCATION_ASSET", *assetsPath); err != nil {
			log.Fatalf("Failed to set XRAY_LOCATION_ASSET: %v", err)
		}
	}

	var dnsList []string
	for s := range strings.SplitSeq(*dnsServers, ",") {
		if s = strings.TrimSpace(s); s != "" {
			dnsList = append(dnsList, s)
		}
	}

	var parsedLocalSocks5, localSocks5User, localSocks5Pass string
	if *localSocks5 != "" {
		parsedLocalSocks5 = *localSocks5
		if parts := strings.SplitN(parsedLocalSocks5, "@", 2); len(parts) == 2 {
			parsedLocalSocks5 = parts[1]
			creds := strings.SplitN(parts[0], ":", 2)
			localSocks5User = creds[0]
			if len(creds) == 2 {
				localSocks5Pass = creds[1]
			}
		}
	}

	var jsonConfig []byte
	// dualOutbound is true whenever the generated config ends up with a "local"/"direct"
	// balancer topology (any dual-route mode below), so the observatory-status goroutine and
	// the stats socket's outbound list can be driven by the same decision instead of each
	// re-deriving it from flags on their own.
	var dualOutbound bool

	// Determine mode based on flags (flags > file > link)
	useWgFlags := *wgPrivateKey != "" && *wgPublicKey != "" && *wgEndpoint != ""

	if fileCfg != nil && fileCfg.RawXray != nil {
		if *link != "" || useWgFlags || *wgConfigPath != "" || parsedLocalSocks5 != "" || *httpSep != "" || len(fileCfg.OutboundPatch) != 0 {
			log.Fatal("Full Xray JSON cannot be combined with generated outbound settings")
		}
		jsonConfig, err = buildRawXrayConfig(fileCfg.RawXray, *listen, *proxyUser, *proxyPass)
		if err != nil {
			log.Fatal("Full Xray JSON rejected: ", err)
		}
	} else if useWgFlags {
		// Mode 1: WireGuard from flags (highest priority)
		log.Println("Using WireGuard config from flags")

		var addrs []string
		if *wgAddress != "" {
			for addr := range strings.SplitSeq(*wgAddress, ",") {
				addrs = append(addrs, strings.TrimSpace(addr))
			}
		}

		iface := &WireGuardInterfaceConfig{
			PrivateKey: *wgPrivateKey,
			Address:    addrs,
			MTU:        *wgMTU,
		}
		peer := &WireGuardPeerConfig{
			PublicKey:    *wgPublicKey,
			PresharedKey: *wgPresharedKey,
			Endpoint:     *wgEndpoint,
			KeepAlive:    *wgKeepAlive,
		}
		jsonConfig, err = buildWireGuardXrayConfig(iface, peer, *listen, *httpSep, dnsList, *debug, *proxyUser, *proxyPass, *routeDirect, *routeBlock, *fakeDNS)
		if err != nil {
			log.Fatal("Failed to build WireGuard Xray configuration from flags:", err)
		}
	} else if *wgConfigPath != "" {
		// Mode 2: WireGuard from config file
		log.Printf("Using WireGuard config from %s", *wgConfigPath)
		iface, peer, err := parseWireGuardConfig(*wgConfigPath)
		if err != nil {
			log.Fatalf("Failed to parse WireGuard config %s: %v", *wgConfigPath, err)
		}
		jsonConfig, err = buildWireGuardXrayConfig(iface, peer, *listen, *httpSep, dnsList, *debug, *proxyUser, *proxyPass, *routeDirect, *routeBlock, *fakeDNS)
		if err != nil {
			log.Fatal("Failed to build WireGuard Xray configuration from file:", err)
		}
	} else if *link != "" {
		// Mode 3: vless/trojan/hysteria2 from link
		cfg, err := parseProxyLink(*link)
		if err != nil {
			log.Fatal("Failed to parse proxy link:", err)
		}
		if cfg.Protocol == "hysteria2" && *muxConcurrency > 0 {
			log.Println("warning: -mux is not applicable to hysteria2 and will be ignored")
		}
		if cfg.Params["allowInsecure"] != "" || cfg.Params["insecure"] != "" {
			log.Println(`warning: "allowInsecure"/"insecure" is no longer supported by this xray-core build and is ignored; use "pinnedPeerCertSha256" instead`)
		}
		// Lock in the TLS SNI from the link's own address before -local-address/-direct-address
		// can override cfg.Address for routing. Otherwise, a link with no explicit sni= param
		// would end up sending the tunnel/CDN address as SNI instead of the real server's,
		// breaking the TLS handshake against the real server's certificate.
		if cfg.Params["sni"] == "" {
			cfg.Params["sni"] = cfg.Address
		}
		var cfgs []*ProxyConfig
		var chainDualRoute bool

		if *socks5Chain {
			if cfg.Protocol == "hysteria2" {
				log.Fatal("-socks5-chain is not supported for hysteria2 (its QUIC dialer can't chain through a SOCKS5 hop)")
			}
			if parsedLocalSocks5 == "" {
				log.Fatal("-socks5-chain requires -local-socks5 to be set")
			}
			if _, _, err := net.SplitHostPort(parsedLocalSocks5); err != nil {
				log.Fatalf("Invalid -local-socks5 %q: %v", *localSocks5, err)
			}
			if *directAddress != "" {
				// Dual route: always reach the same server, preferring a direct connection
				// and falling back to the same address dialed through -local-socks5 (chained)
				// if the direct path is unreachable.
				chainDualRoute = true
				dualOutbound = true
				if *localAddress != "" {
					log.Fatal("-local-address cannot be used together with -socks5-chain and -direct-address")
				}
				host, port := parseHostPort("-direct-address", *directAddress)
				cfg.Address = host
				cfg.Port = port
				cfgs = []*ProxyConfig{cfg}
				log.Printf("Using load balancer: direct route to %s:%d, SOCKS5-chained (%s) route to the same address", host, port, parsedLocalSocks5)
			} else {
				if *localAddress != "" {
					host, port := parseHostPort("-local-address", *localAddress)
					cfg.Address = host
					cfg.Port = port
				}
				cfgs = []*ProxyConfig{cfg}
				log.Printf("Using %s chained through local SOCKS5 %s", cfg.Protocol, parsedLocalSocks5)
			}
		} else if parsedLocalSocks5 != "" {
			if *directAddress == "" {
				log.Fatal("When used with -link, -local-socks5 requires -direct-address to be specified for load balancing")
			}
			if *localAddress != "" {
				log.Fatal("-local-socks5 and -local-address cannot be used together")
			}
			if _, _, err := net.SplitHostPort(parsedLocalSocks5); err != nil {
				log.Fatalf("Invalid -local-socks5 %q: %v", *localSocks5, err)
			}
			host, port := parseHostPort("-direct-address", *directAddress)
			cfg.Address = host
			cfg.Port = port
			cfgs = []*ProxyConfig{cfg}
			dualOutbound = true
			log.Printf("Using load balancer: SOCKS5 local route %s, %s direct route %s:%d", parsedLocalSocks5, cfg.Protocol, host, port)
		} else {
			if *localAddress != "" {
				host, port := parseHostPort("-local-address", *localAddress)
				cfg.Address = host
				cfg.Port = port
			}

			cfgs = []*ProxyConfig{cfg}

			if *directAddress != "" {
				host, port := parseHostPort("-direct-address", *directAddress)
				cfg2, err := parseProxyLink(*link)
				if err != nil {
					log.Fatal("Failed to parse proxy link for direct config:", err)
				}
				if cfg2.Params["sni"] == "" {
					cfg2.Params["sni"] = cfg2.Address
				}
				cfg2.Address = host
				cfg2.Port = port
				cfgs = append(cfgs, cfg2)
				dualOutbound = true
				log.Printf("Using %s with load balancer: local route %s:%d, direct route %s:%d", cfg.Protocol, cfg.Address, cfg.Port, host, port)
			} else {
				log.Printf("Using %s config from link", cfg.Protocol)
			}
		}

		jsonConfig, err = buildXrayConfig(cfgs, parsedLocalSocks5, localSocks5User, localSocks5Pass, *listen, *httpSep, dnsList, *debug, *hcInterval, *muxConcurrency, *proxyUser, *proxyPass, *routeDirect, *routeBlock, *hcDestination, *fakeDNS, *socks5Chain, chainDualRoute)
		if err != nil {
			log.Fatal("Failed to build Xray configuration:", err)
		}
	} else if parsedLocalSocks5 != "" {
		// Mode 4: Standalone SOCKS5 proxy
		log.Printf("Using standalone SOCKS5 upstream from %s", parsedLocalSocks5)
		_, _, err := net.SplitHostPort(parsedLocalSocks5)
		if err != nil {
			log.Fatalf("Invalid -local-socks5 %q: %v", *localSocks5, err)
		}
		jsonConfig, err = buildSocks5XrayConfig(parsedLocalSocks5, localSocks5User, localSocks5Pass, *listen, *httpSep, dnsList, *debug, *proxyUser, *proxyPass, *routeDirect, *routeBlock, *fakeDNS)
		if err != nil {
			log.Fatal("Failed to build SOCKS5 Xray configuration:", err)
		}
	} else {
		log.Fatal("No configuration provided. Use -link (vless/trojan/hysteria2), -wg (for WireGuard file), wg-* flags, or -local-socks5.")
	}

	if fileCfg != nil && fileCfg.RawXray == nil {
		jsonConfig, err = applyOutboundPatch(jsonConfig, fileCfg.OutboundPatch)
		if err != nil {
			log.Fatal("Outbound settings: ", err)
		}
	}
	// Load config using serial.LoadJSONConfig (requires io.Reader)
	config, err := serial.LoadJSONConfig(strings.NewReader(string(jsonConfig)))
	if err != nil {
		if fileCfg != nil && fileCfg.RawXray != nil { log.Fatal("Full Xray JSON rejected by the bundled core; check protocol settings and installed geo assets") }
		log.Fatal("Failed to load configuration:", err)
	}

	server, err := core.New(config)
	if err != nil {
		if fileCfg != nil && fileCfg.RawXray != nil { log.Fatal("Full Xray JSON instance initialization failed") }
		log.Fatal("Failed to create Xray instance:", err)
	}

	if *checkConfig || *dumpConfig {
		defer server.Close()
		if *dumpConfig {
			fmt.Println(string(jsonConfig))
		} else {
			fmt.Println("Configuration valid")
		}
		return
	}
	if err := server.Start(); err != nil {
		log.Fatal("Failed to start Xray:", err)
	}

	if *httpSep != "" {
		log.Printf("Xray started -> SOCKS5 on %s, HTTP on %s", *listen, *httpSep)
	} else {
		log.Printf("Xray started -> proxy on %s", *listen)
	}

	if dualOutbound {
		go func() {
			prev := ""
			ticker := time.NewTicker(5 * time.Second)
			defer ticker.Stop()
			for range ticker.C {
				feat := server.GetFeature(extension.ObservatoryType())
				obs, ok := feat.(extension.Observatory)
				if !ok {
					continue
				}
				result, err := obs.GetObservation(context.Background())
				if err != nil {
					continue
				}
				or, ok := result.(*observatory.ObservationResult)
				if !ok {
					continue
				}
				statuses := or.GetStatus()
				if len(statuses) == 0 {
					if prev != "pending" {
						log.Println("active route: pending (waiting for first check)")
						prev = "pending"
					}
					continue
				}

				// Mirror balancer logic: direct if alive, else fallback to local.
				alive := make(map[string]int64) // tag → delay
				for _, s := range statuses {
					if s.GetAlive() {
						alive[s.GetOutboundTag()] = s.GetDelay()
					}
				}
				active := "local" // fallback
				if d, ok := alive["direct"]; ok {
					_ = d
					active = "direct"
				}

				if active != prev {
					switch active {
					case "direct":
						log.Printf("active route: direct (delay %dms)", alive["direct"])
					case "local":
						if d, ok := alive["local"]; ok {
							log.Printf("active route: local (delay %dms, direct unreachable)", d)
						} else {
							log.Println("active route: local (fallback, both unreachable)")
						}
					}
					prev = active
				}
			}
		}()
	}

	outboundTags := []string{"proxy"}
	if dualOutbound {
		outboundTags = []string{"local", "direct"}
	}
	startStatsSocket(*statsSocket, server, outboundTags)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	server.Close()
	log.Println("Xray stopped")
}
