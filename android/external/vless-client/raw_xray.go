package main

import (
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"strings"
)

// Accept only the app-adapted private document. Native routing, DNS and balancers
// intentionally bypass the link builder and the generated TUNA policy overlay.
func buildRawXrayConfig(config map[string]any, listen, user, password string) ([]byte, error) {
	allowed := map[string]bool{"log": true, "dns": true, "routing": true, "inbounds": true,
		"outbounds": true, "policy": true, "stats": true, "observatory": true,
		"burstObservatory": true, "fakedns": true}
	for key := range config {
		if !allowed[key] {
			return nil, fmt.Errorf("unsupported top-level section")
		}
	}
	host, portText, err := net.SplitHostPort(listen)
	if err != nil {
		return nil, fmt.Errorf("invalid local listener")
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 || (host != "127.0.0.1" && host != "::1") {
		return nil, fmt.Errorf("listener must be loopback")
	}
	// Normalize YAML maps/numeric types, then inspect without including values in errors.
	raw, err := json.Marshal(config)
	if err != nil || len(raw) > 4*1024*1024 {
		return nil, fmt.Errorf("invalid document size or shape")
	}
	var document map[string]any
	if err := json.Unmarshal(raw, &document); err != nil {
		return nil, fmt.Errorf("invalid JSON")
	}
	inbounds, ok := document["inbounds"].([]any)
	if !ok || len(inbounds) != 1 {
		return nil, fmt.Errorf("one app-owned listener required")
	}
	inbound, ok := inbounds[0].(map[string]any)
	if !ok || inbound["protocol"] != "socks" || inbound["listen"] != host || inbound["port"] != float64(port) || inbound["tag"] != "tuna-json-in" {
		return nil, fmt.Errorf("invalid app-owned SOCKS listener")
	}
	settings, ok := inbound["settings"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("missing SOCKS settings")
	}
	if user == "" && password == "" {
		if settings["auth"] != "noauth" {
			return nil, fmt.Errorf("invalid local authentication")
		}
	} else {
		if user == "" || password == "" || settings["auth"] != "password" {
			return nil, fmt.Errorf("local authentication required")
		}
		accounts, ok := settings["accounts"].([]any)
		if !ok || len(accounts) != 1 {
			return nil, fmt.Errorf("invalid local accounts")
		}
		account, ok := accounts[0].(map[string]any)
		if !ok || account["user"] != user || account["pass"] != password {
			return nil, fmt.Errorf("local credentials mismatch")
		}
	}
	outs, ok := document["outbounds"].([]any)
	if !ok || len(outs) < 1 || len(outs) > 2048 {
		return nil, fmt.Errorf("outbounds required")
	}
	for _, value := range outs {
		outbound, ok := value.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("invalid outbound")
		}
		if outbound["protocol"] == "wireguard" {
			settings, ok := outbound["settings"].(map[string]any)
			if !ok || settings["noKernelTun"] != true {
				return nil, fmt.Errorf("WireGuard requires noKernelTun=true")
			}
		}
	}
	var walk func(any, string) error
	walk = func(value any, path string) error {
		switch node := value.(type) {
		case map[string]any:
			for key, child := range node {
				switch key {
				case "certificateFile", "keyFile", "masterKeyLog", "masqFile", "geodata", "interface", "mark", "tproxy", "kernelMode", "reverse", "sendThrough":
					return fmt.Errorf("external files and system network bindings are unavailable")
				case "dir":
					if strings.HasSuffix(path, "masquerade") {
						return fmt.Errorf("file masquerade is unavailable")
					}
				case "system":
					if path != "policy" {
						return fmt.Errorf("system network mode is unavailable")
					}
				}
				next := key
				if path != "" {
					next = path + "." + key
				}
				if err := walk(child, next); err != nil {
					return err
				}
			}
		case []any:
			for _, child := range node {
				if err := walk(child, path); err != nil {
					return err
				}
			}
		case string:
			if strings.HasPrefix(strings.ToLower(node), "ext:") || strings.HasPrefix(strings.ToLower(node), "ext-ip:") {
				return fmt.Errorf("external geo files are unavailable")
			}
		}
		return nil
	}
	if err := walk(document, ""); err != nil {
		return nil, err
	}
	// Enforce diagnostics even for manually constructed wrapper input.
	document["log"] = map[string]any{"loglevel": "warning", "access": "none", "dnsLog": false}
	return json.Marshal(document)
}
