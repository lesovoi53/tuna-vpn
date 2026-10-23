package client

import (
	"masterdnsvpn-go/internal/config"
	"testing"
)

func TestTunaBuildConnectionsUsesOnlySelectedDomainResolverPairs(t *testing.T) {
	cfg := config.ClientConfig{Domains: []string{"a.test", "b.test", "disabled.test"}, TunaDomainResolvers: map[string][]string{
		"a.test": {"192.0.2.1:53"}, "b.test": {"192.0.2.2:53", "192.0.2.1:5353"},
	}}
	if err := cfg.PrepareTunaDomainResolvers(); err != nil {
		t.Fatal(err)
	}
	c := New(cfg, nil, nil)
	if err := c.BuildConnectionMap(); err != nil {
		t.Fatal(err)
	}
	connections := c.balancer.AllConnections()
	if len(connections) != 3 {
		t.Fatalf("wanted 3 exact pairs, got %+v", connections)
	}
	for _, connection := range connections {
		if !cfg.TunaResolverAllowed(connection.Domain, config.ResolverAddress{IP: connection.Resolver, Port: connection.ResolverPort}) {
			t.Fatal("unexpected pair", connection)
		}
	}
	// A rebuild during recovery must not recreate the Cartesian product.
	if err := c.BuildConnectionMap(); err != nil {
		t.Fatal(err)
	}
	if len(c.balancer.AllConnections()) != 3 {
		t.Fatal("rebuild lost restrictions")
	}
}
