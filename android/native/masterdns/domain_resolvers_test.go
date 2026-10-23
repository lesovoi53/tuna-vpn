package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestTunaDomainResolversLoadedFromTOML(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "client.toml")
	data := `DOMAINS = ["a.test", "b.test", "off.test"]
TUNA_DOMAIN_RESOLVERS = { "a.test" = ["192.0.2.1:53"], "b.test" = ["192.0.2.1:5353"], "off.test" = [] }
ENCRYPTION_KEY = "synthetic-test-key"
DATA_ENCRYPTION_METHOD = 0
`
	if err := os.WriteFile(path, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "client_resolvers.txt"), []byte("192.0.2.1:53\n192.0.2.1:5353\n"), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadClientConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Resolvers) != 2 || cfg.TunaDomainResolvers == nil {
		t.Fatal("TOML lost endpoint restrictions")
	}
	if cfg.TunaResolverAllowed("off.test", ResolverAddress{"192.0.2.1", 53}) {
		t.Fatal("disabled domain enabled")
	}
}

func TestTunaDomainResolversNormalizeExactEndpoints(t *testing.T) {
	c := ClientConfig{Domains: []string{"a.test", "b.test", "off.test"}, TunaDomainResolvers: map[string][]string{
		"a.test": {"192.0.2.1", "[2001:db8::1]:5353"}, "b.test": {"192.0.2.1:5353"}, "off.test": {},
	}}
	if err := c.PrepareTunaDomainResolvers(); err != nil {
		t.Fatal(err)
	}
	if len(c.Resolvers) != 3 {
		t.Fatalf("ports lost: %+v", c.Resolvers)
	}
	if !c.TunaResolverAllowed("a.test", ResolverAddress{"192.0.2.1", 53}) ||
		c.TunaResolverAllowed("a.test", ResolverAddress{"192.0.2.1", 5353}) ||
		c.TunaResolverAllowed("off.test", ResolverAddress{"192.0.2.1", 53}) {
		t.Fatal("pair isolation failed")
	}
}

func TestTunaDomainResolversRejectEmptyUnknownAndInvalid(t *testing.T) {
	for _, selected := range []map[string][]string{
		{}, {"a.test": {}}, {"other.test": {"192.0.2.1"}},
		{"a.test": {"example.com"}}, {"a.test": {"192.0.2.1:0"}}, {"a.test": {"[fe80::1%wlan0]:53"}},
	} {
		c := ClientConfig{Domains: []string{"a.test"}, TunaDomainResolvers: selected}
		if err := c.PrepareTunaDomainResolvers(); err == nil {
			t.Fatal("accepted invalid selection", selected)
		}
	}
	c := ClientConfig{}
	if err := c.PrepareTunaDomainResolvers(); err != nil {
		t.Fatal("legacy rejected", err)
	}
	if !c.TunaResolverAllowed("legacy.test", ResolverAddress{"192.0.2.1", 53}) {
		t.Fatal("legacy pool changed")
	}
}
