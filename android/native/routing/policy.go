package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
)

type tunaRule struct {
	Match   string `json:"match"`
	Action  string `json:"action"`
	Enabled bool   `json:"enabled"`
}
type tunaResolver struct {
	Transport   string `json:"transport"`
	Endpoint    string `json:"endpoint"`
	BootstrapIP string `json:"bootstrapIp"`
	Route       string `json:"route"`
}
type tunaRecovery struct {
	Mode     string        `json:"mode"`
	Resolver *tunaResolver `json:"resolver"`
}
type tunaSplitDNS struct {
	Remote                tunaResolver      `json:"remote"`
	Domestic              tunaResolver      `json:"domestic"`
	RemoteServers         []tunaResolver    `json:"remoteServers"`
	DomesticServers       []tunaResolver    `json:"domesticServers"`
	RemoteSelectionMode   string            `json:"remoteSelectionMode"`
	DomesticSelectionMode string            `json:"domesticSelectionMode"`
	TimeoutMs             int               `json:"timeoutMs"`
	RemoteEmergency       *tunaRecovery     `json:"remoteEmergency"`
	DomesticEmergency     *tunaRecovery     `json:"domesticEmergency"`
	Bootstrap             *tunaRecovery     `json:"bootstrap"`
	Hosts                 map[string]string `json:"hosts"`
	QueryStrategy         string            `json:"queryStrategy"`
	DisableCache          bool              `json:"disableCache"`
}
type tunaPolicy struct {
	Mode           string        `json:"mode"`
	Rules          []tunaRule    `json:"rules"`
	DNS            *tunaSplitDNS `json:"splitDns"`
	DomainStrategy string        `json:"domainStrategy"`
	Sniffing       *bool         `json:"sniffing"`
}

// Changes the generated routing table only. The existing proxy/balancer and
// protocol configuration remain authoritative; DNS requests always use VPN.
func applyTunaPolicy(raw []byte, path string) ([]byte, error) {
	if path == "" {
		return raw, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var policy tunaPolicy
	if err = json.Unmarshal(data, &policy); err != nil {
		return nil, err
	}
	if policy.Mode != "ALL_VPN" && policy.Mode != "SELECTED_VPN" && policy.Mode != "SELECTED_DIRECT" {
		return nil, fmt.Errorf("invalid routing mode")
	}
	var c map[string]any
	if err = json.Unmarshal(raw, &c); err != nil {
		return nil, err
	}
	routing, ok := c["routing"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("missing routing table")
	}
	old, ok := routing["rules"].([]any)
	if !ok || len(old) == 0 {
		return nil, fmt.Errorf("missing default route")
	}
	fallback, ok := old[len(old)-1].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("invalid default route")
	}
	target := map[string]any{}
	for _, key := range []string{"outboundTag", "balancerTag"} {
		if v, found := fallback[key]; found {
			target[key] = v
		}
	}
	if len(target) != 1 {
		return nil, fmt.Errorf("ambiguous default route")
	}
	makeRule := func(action string) (map[string]any, error) {
		r := map[string]any{"type": "field"}
		switch action {
		case "VPN":
			for k, v := range target {
				r[k] = v
			}
		case "DIRECT":
			r["outboundTag"] = "tuna-bypass"
		case "BLOCK":
			r["outboundTag"] = "tuna-block"
		default:
			return nil, fmt.Errorf("invalid routing action")
		}
		return r, nil
	}
	outs := c["outbounds"].([]any)
	c["outbounds"] = append(outs, map[string]any{"tag": "tuna-bypass", "protocol": "freedom", "settings": map[string]any{}, "streamSettings": map[string]any{"sockopt": map[string]any{"domainStrategy": "UseIP"}}}, map[string]any{"tag": "tuna-block", "protocol": "blackhole", "settings": map[string]any{}})
	var rules []any
	// Preserve native DNS interception before user rules. Built-in resolver traffic
	// is tagged so even SELECTED_VPN cannot send it to the direct default route.
	var clientTags []string
	if inbounds, ok := c["inbounds"].([]any); ok {
		for i, item := range inbounds {
			if inbound, ok := item.(map[string]any); ok {
				if policy.Sniffing != nil && *policy.Sniffing {
					if sniff, ok := inbound["sniffing"].(map[string]any); ok {
						if overrides, ok := sniff["destOverride"].([]any); ok {
							found := false
							for _, v := range overrides {
								if v == "quic" {
									found = true
								}
							}
							if !found {
								sniff["destOverride"] = append(overrides, "quic")
							}
						}
					}
				}
				if policy.Sniffing != nil && !*policy.Sniffing {
					// Preserve proxy-only FakeDNS restoration, even when TLS/HTTP sniff is off.
					if sniff, ok := inbound["sniffing"].(map[string]any); ok {
						keep := []string{}
						if overrides, ok := sniff["destOverride"].([]any); ok {
							for _, v := range overrides {
								if v == "fakedns" {
									keep = append(keep, "fakedns")
								}
							}
						}
						sniff["enabled"] = len(keep) > 0
						sniff["destOverride"] = keep
					}
				}
				tag, _ := inbound["tag"].(string)
				if tag == "" {
					tag = fmt.Sprintf("tuna-client-%d", i)
					inbound["tag"] = tag
				}
				clientTags = append(clientTags, tag)
			}
		}
	}
	for _, item := range old {
		r, ok := item.(map[string]any)
		if ok && r["outboundTag"] == "dns-out" {
			if len(clientTags) == 0 {
				return nil, fmt.Errorf("DNS interception requires client inbounds")
			}
			// Only intercept client requests. Intercepting the built-in resolver's TCP
			// connection back to port 53 would recursively invoke that same resolver.
			r["network"] = "tcp,udp"
			r["inboundTag"] = clientTags
			rules = append(rules, r)
		}
	}
	dnsRule, _ := makeRule("VPN")
	dnsRule["inboundTag"] = []string{"tuna-dns"}
	rules = append(rules, dnsRule)
	if policy.DNS != nil {
		for _, item := range []struct{ tag, action string }{{"tuna-dns-remote", "VPN"}, {"tuna-dns-domestic", "DIRECT"}, {"tuna-dns-block", "BLOCK"}, {"tuna-dns-bootstrap", "DIRECT"}} {
			r, _ := makeRule(item.action)
			r["inboundTag"] = []string{item.tag}
			rules = append(rules, r)
		}
	}
	dnsPortRule, _ := makeRule("VPN")
	dnsPortRule["network"] = "tcp,udp"
	dnsPortRule["port"] = "53"
	rules = append(rules, dnsPortRule)
	if dns, ok := c["dns"].(map[string]any); ok {
		dns["tag"] = "tuna-dns"
	}
	strategy := "AsIs"
	if len(policy.Rules) > 500 {
		return nil, fmt.Errorf("too many routing rules")
	}
	for _, item := range policy.Rules {
		if !item.Enabled || (policy.Mode == "ALL_VPN" && item.Action != "BLOCK") {
			continue
		}
		if strings.TrimSpace(item.Match) == "" || strings.ContainsAny(item.Match, "\n\r") {
			return nil, fmt.Errorf("invalid routing match")
		}
		var domains, ips []string
		if strings.HasPrefix(item.Match, "regexp:") {
			// A regex is one matcher: commas in repetition ranges must not split it.
			domains = []string{item.Match}
		} else {
			if strings.Contains(item.Match, ",") {
				return nil, fmt.Errorf("invalid routing match")
			}
			domains, ips = parseRouteSpec(item.Match)
		}
		// Xray combines domain+ip fields with AND. Separate rules implement OR.
		if len(domains) > 0 {
			r, e := makeRule(item.Action)
			if e != nil {
				return nil, e
			}
			r["domain"] = domains
			rules = append(rules, r)
		}
		if len(ips) > 0 {
			r, e := makeRule(item.Action)
			if e != nil {
				return nil, e
			}
			r["ip"] = ips
			rules = append(rules, r)
			strategy = "IPIfNonMatch"
		}
	}
	action := "VPN"
	if policy.Mode == "SELECTED_VPN" {
		action = "DIRECT"
	}
	last, _ := makeRule(action)
	last["network"] = "tcp,udp"
	rules = append(rules, last)
	routing["rules"] = rules
	routing["domainStrategy"] = strategy
	if policy.DomainStrategy != "" {
		if policy.DomainStrategy != "AsIs" && policy.DomainStrategy != "IPIfNonMatch" && policy.DomainStrategy != "IPOnDemand" {
			return nil, fmt.Errorf("invalid domain strategy")
		}
		routing["domainStrategy"] = policy.DomainStrategy
	}
	if policy.DNS != nil {
		if err := applySplitDNS(c, policy); err != nil {
			return nil, err
		}
	}
	return json.Marshal(c)
}

func applySplitDNS(c map[string]any, policy tunaPolicy) error {
	bootstrap, err := tunaBootstrapAddress(policy.DNS.Bootstrap)
	if err != nil {
		return err
	}
	if bootstrap == "" {
		bootstrap = "tcp://1.1.1.1:53"
	}
	bootstrapRoute := ""
	if policy.DNS.Bootstrap != nil {
		bootstrapRoute = "DIRECT"
	}
	hosts := map[string]any{}
	for name, ip := range policy.DNS.Hosts {
		if net.ParseIP(ip) == nil {
			return fmt.Errorf("invalid static DNS address")
		}
		hosts[name] = ip
	}
	endpoint := func(r tunaResolver) (string, error) {
		address := r.Endpoint
		if r.Transport == "DOH" {
			u, e := url.Parse(address)
			if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
				return "", fmt.Errorf("invalid DoH endpoint")
			}
			if net.ParseIP(u.Hostname()) == nil {
				if r.BootstrapIP != "" {
					if net.ParseIP(r.BootstrapIP) == nil {
						return "", fmt.Errorf("invalid DoH bootstrap IP")
					}
					if old, ok := hosts[u.Hostname()]; ok && old != r.BootstrapIP {
						return "", fmt.Errorf("conflicting DNS bootstrap")
					}
					hosts[u.Hostname()] = r.BootstrapIP
				} else if _, ok := policy.DNS.Hosts[u.Hostname()]; !ok {
					// Resolve only this endpoint through a literal bootstrap server on the
					// calling pool's route. It never becomes a fallback for user questions.
					b, e := json.Marshal(map[string]string{"endpoint": address, "bootstrap": bootstrap, "bootstrapRoute": bootstrapRoute})
					if e != nil {
						return "", e
					}
					address = "tuna-doh://" + base64.RawURLEncoding.EncodeToString(b)
				}
			}
		} else if r.Transport == "TCP" || r.Transport == "UDP" {
			// A literal resolver address avoids a recursive bootstrap dependency.
			host := address
			if h, _, e := net.SplitHostPort(address); e == nil {
				host = h
			}
			if net.ParseIP(strings.Trim(host, "[]")) == nil {
				return "", fmt.Errorf("DNS resolver must be an IP")
			}
			if r.Transport == "TCP" {
				address = "tcp://" + address
			}
		} else {
			return "", fmt.Errorf("unsupported DNS transport")
		}
		return address, nil
	}
	timeout := policy.DNS.TimeoutMs
	if timeout == 0 {
		timeout = 5000
	}
	if timeout < 1000 || timeout > 10000 {
		return fmt.Errorf("invalid DNS total timeout")
	}
	pool := func(group string, legacy tunaResolver, resolvers []tunaResolver, mode string, emergency *tunaRecovery) (string, error) {
		legacyOnly := resolvers == nil && mode == "" && policy.DNS.TimeoutMs == 0 && emergency == nil
		if resolvers == nil {
			resolvers = []tunaResolver{legacy}
		}
		if len(resolvers) < 1 || len(resolvers) > 8 {
			return "", fmt.Errorf("DNS pool requires 1..8 resolvers")
		}
		if mode == "" {
			mode = "auto"
		}
		if mode != "auto" && mode != "priority" {
			return "", fmt.Errorf("invalid DNS selection mode")
		}
		addresses := []string{}
		seen := map[string]bool{}
		for _, r := range resolvers {
			address, e := endpoint(r)
			if e != nil {
				return "", e
			}
			if r.Transport == "UDP" {
				if net.ParseIP(strings.Trim(address, "[]")) != nil {
					address = net.JoinHostPort(strings.Trim(address, "[]"), "53")
				}
			}
			if seen[address] {
				return "", fmt.Errorf("duplicate DNS resolver")
			}
			seen[address] = true
			addresses = append(addresses, address)
		}
		poolTimeout := timeout
		if legacyOnly {
			mode = "legacy"
			poolTimeout = 0
		}
		spec := map[string]any{"group": group, "mode": mode, "timeoutMs": poolTimeout, "addresses": addresses}
		if emergency != nil {
			switch emergency.Mode {
			case "system":
				if emergency.Resolver != nil {
					return "", fmt.Errorf("system emergency cannot specify resolver")
				}
				spec["emergency"] = "tuna-system://android"
			case "disabled":
				if emergency.Resolver != nil {
					return "", fmt.Errorf("disabled emergency cannot specify resolver")
				}
			case "resolver":
				if emergency.Resolver == nil {
					return "", fmt.Errorf("missing emergency resolver")
				}
				route := "VPN"
				if group == "domestic" {
					route = "DIRECT"
				}
				if emergency.Resolver.Route != "" && emergency.Resolver.Route != route {
					return "", fmt.Errorf("emergency resolver route mismatch")
				}
				address, e := endpoint(*emergency.Resolver)
				if e != nil {
					return "", e
				}
				if emergency.Resolver.Transport == "UDP" && net.ParseIP(strings.Trim(address, "[]")) != nil {
					address = net.JoinHostPort(strings.Trim(address, "[]"), "53")
				}
				spec["emergency"] = address
			default:
				return "", fmt.Errorf("invalid emergency DNS mode")
			}
		}
		b, e := json.Marshal(spec)
		if e != nil {
			return "", e
		}
		return "tuna-dns://" + base64.RawURLEncoding.EncodeToString(b), nil
	}
	remote, err := pool("remote", policy.DNS.Remote, policy.DNS.RemoteServers, policy.DNS.RemoteSelectionMode, policy.DNS.RemoteEmergency)
	if err != nil {
		return err
	}
	domestic, err := pool("domestic", policy.DNS.Domestic, policy.DNS.DomesticServers, policy.DNS.DomesticSelectionMode, policy.DNS.DomesticEmergency)
	if err != nil {
		return err
	}
	server := func(action string, domains []string) map[string]any {
		address, tag := remote, "tuna-dns-remote"
		if action == "DIRECT" {
			address, tag = domestic, "tuna-dns-domestic"
		}
		if action == "BLOCK" {
			tag = "tuna-dns-block"
		}
		s := map[string]any{"address": address, "tag": tag, "finalQuery": true}
		legacyOnly := policy.DNS.RemoteServers == nil && policy.DNS.RemoteSelectionMode == "" && policy.DNS.TimeoutMs == 0 && policy.DNS.RemoteEmergency == nil
		if action == "DIRECT" {
			legacyOnly = policy.DNS.DomesticServers == nil && policy.DNS.DomesticSelectionMode == "" && policy.DNS.TimeoutMs == 0 && policy.DNS.DomesticEmergency == nil
		}
		if strings.HasPrefix(address, "tuna-dns://") && !legacyOnly {
			s["timeoutMs"] = timeout
		}
		if len(domains) > 0 {
			s["domains"] = domains
			s["skipFallback"] = true
		}
		return s
	}
	var servers []any
	for _, r := range policy.Rules {
		if !r.Enabled || (policy.Mode == "ALL_VPN" && r.Action != "BLOCK") {
			continue
		}
		domains, _ := parseRouteSpec(r.Match)
		if len(domains) > 0 {
			servers = append(servers, server(r.Action, domains))
		}
	}
	fallback := "VPN"
	if policy.Mode == "SELECTED_VPN" {
		fallback = "DIRECT"
	}
	servers = append(servers, server(fallback, nil))
	strategy := policy.DNS.QueryStrategy
	if strategy == "" {
		strategy = "UseIP"
	}
	if strategy != "UseIP" && strategy != "UseIPv4" && strategy != "UseIPv6" {
		return fmt.Errorf("invalid DNS query strategy")
	}
	c["dns"] = map[string]any{"queryStrategy": strategy, "disableCache": policy.DNS.DisableCache, "servers": servers, "hosts": hosts, "disableFallbackIfMatch": true, "tag": "tuna-dns", "enableParallelQuery": false}
	return nil
}

func tunaDNSAddress(address string) string {
	if strings.Contains(address, "://") || address == "localhost" || address == "fakedns" {
		return address
	}
	return "tcp://" + address
}

func applyTunaSocksResolution(raw []byte, enabled bool) ([]byte, error) {
	if !enabled {
		return raw, nil
	}
	var c map[string]any
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, err
	}
	// Resolve the payload destination through Xray's configured DNS AFTER routing.
	// This also covers TLS/HTTP sniffing and FakeDNS restoration, without changing
	// DIRECT/BLOCK decisions or resolving user traffic with the bootstrap resolver.
	strategy := "ForceIPv4v6"
	if dns, ok := c["dns"].(map[string]any); ok {
		switch dns["queryStrategy"] {
		case "UseIPv4":
			strategy = "ForceIPv4"
		case "UseIPv6":
			strategy = "ForceIPv6"
		}
	}
	outs, _ := c["outbounds"].([]any)
	for _, item := range outs {
		if out, ok := item.(map[string]any); ok {
			// HEV MAPDNS restores names, not real IPs. Resolve payload destinations
			// before handing them to the remote proxy, including native VLESS.
			// Leave DNS, freedom and blackhole outbounds on their own routing paths.
			switch out["protocol"] {
			case "socks", "vless", "vmess", "trojan", "shadowsocks":
				out["targetStrategy"] = strategy
			}
		}
	}
	return json.Marshal(c)
}
