package main

import (
	"masterdnsvpn-go/internal/config"
	"os"
	"path/filepath"
	"testing"
)

func TestDiagnosticKeepsDistinctLoopbackPorts(t *testing.T) {
	path := filepath.Join(t.TempDir(), "resolvers.txt")
	if err := os.WriteFile(path, []byte("127.0.0.1:12001\n127.0.0.1:12002\n127.0.0.1:12001\n"), 0600); err != nil {
		t.Fatal(err)
	}
	resolvers, err := scanResolverEndpoints(path)
	if err != nil || len(resolvers) != 2 || resolvers[0].Port == resolvers[1].Port {
		t.Fatalf("endpoint identity lost: %+v %v", resolvers, err)
	}
	cfg := config.ClientConfig{ListenIP: "127.0.0.1", SOCKS5Auth: true, SOCKS5User: "fixture", SOCKS5Pass: "fixture", ProtocolType: "SOCKS5", Resolvers: resolvers}
	if err = validateDiagnosticConfig(cfg); err != nil {
		t.Fatal(err)
	}
	cfg.Resolvers = []config.ResolverAddress{{IP: "192.0.2.1", Port: 53}}
	if validateDiagnosticConfig(cfg) == nil {
		t.Fatal("direct external resolver accepted")
	}
}

func TestDiagnosticRejectsUnauthenticatedOrNonLoopbackListener(t *testing.T) {
	cfg := config.ClientConfig{ListenIP: "127.0.0.1", SOCKS5Auth: true, SOCKS5User: "fixture", SOCKS5Pass: "fixture", ProtocolType: "SOCKS5"}
	for _, change := range []func(*config.ClientConfig){
		func(c *config.ClientConfig) { c.SOCKS5Auth = false },
		func(c *config.ClientConfig) { c.SOCKS5Pass = "" },
		func(c *config.ClientConfig) { c.ListenIP = "0.0.0.0" },
		func(c *config.ClientConfig) { c.LocalDNSEnabled = true },
	} {
		bad := cfg
		change(&bad)
		if validateDiagnosticConfig(bad) == nil {
			t.Fatal("unsafe diagnostic config accepted")
		}
	}
}
