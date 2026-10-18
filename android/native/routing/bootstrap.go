package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/url"
	"os"
	"time"

	xdns "github.com/xtls/xray-core/app/dns"
)

// Bootstrap is deliberately separate from Xray's DNS dispatcher: resolving the
// VPN server through that same outbound creates a recursive connection dependency.
func configureBootstrapResolver(value string) error {
	if value == "" {
		return nil
	}
	if value == "system" {
		net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) { return xdns.DialTunaSystemDNS(ctx) }}
		return nil
	}
	endpoint, err := url.Parse(value)
	if err != nil || endpoint.Scheme != "tcp" || endpoint.User != nil || endpoint.Path != "" || endpoint.RawQuery != "" || endpoint.Fragment != "" || net.ParseIP(endpoint.Hostname()) == nil {
		return fmt.Errorf("bootstrap DNS requires tcp://literal-IP:port")
	}
	port := endpoint.Port()
	if port == "" {
		port = "53"
	}
	address := net.JoinHostPort(endpoint.Hostname(), port)
	parsed, err := net.ResolveTCPAddr("tcp", address)
	if err != nil || parsed.Port < 1 || parsed.Port > 65535 {
		return fmt.Errorf("invalid bootstrap DNS port")
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, "tcp", address)
		},
	}
	return nil
}

// An absent field retains the adapter's legacy environment/default behavior.
func tunaBootstrapAddress(setting *tunaRecovery) (string, error) {
	if setting == nil {
		return os.Getenv("TUNA_BOOTSTRAP_DNS"), nil
	}
	if setting.Mode == "system" && setting.Resolver == nil {
		return "system", nil
	}
	if setting.Mode != "resolver" || setting.Resolver == nil {
		return "", fmt.Errorf("invalid bootstrap mode")
	}
	resolver := setting.Resolver
	if resolver.Transport != "TCP" || resolver.BootstrapIP != "" || (resolver.Route != "" && resolver.Route != "DIRECT") {
		return "", fmt.Errorf("bootstrap requires direct TCP literal IP")
	}
	endpoint, err := url.Parse("tcp://" + resolver.Endpoint)
	if err != nil || endpoint.User != nil || endpoint.Path != "" || endpoint.RawQuery != "" || endpoint.Fragment != "" || net.ParseIP(endpoint.Hostname()) == nil {
		return "", fmt.Errorf("bootstrap requires literal IP")
	}
	port := endpoint.Port()
	if port == "" {
		port = "53"
	}
	address := net.JoinHostPort(endpoint.Hostname(), port)
	parsed, err := net.ResolveTCPAddr("tcp", address)
	if err != nil || parsed.Port < 1 || parsed.Port > 65535 {
		return "", fmt.Errorf("invalid bootstrap port")
	}
	return "tcp://" + address, nil
}

// Called only for generated profiles. Full raw Xray configurations own their
// DNS/routing and must not depend on an unrelated application policy file.
func configureTunaPolicyBootstrap(path string) error {
	var setting *tunaRecovery
	if path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			return fmt.Errorf("bootstrap policy unavailable")
		}
		var policy tunaPolicy
		if json.Unmarshal(data, &policy) != nil {
			return fmt.Errorf("invalid bootstrap policy")
		}
		if policy.DNS != nil {
			setting = policy.DNS.Bootstrap
		}
	}
	value, err := tunaBootstrapAddress(setting)
	if err != nil {
		return err
	}
	return configureBootstrapResolver(value)
}

func init() {
	if err := configureBootstrapResolver(os.Getenv("TUNA_BOOTSTRAP_DNS")); err != nil {
		log.Fatal(err)
	}
}
