package config

import (
	"fmt"
	"net/netip"
	"strings"
)

// PrepareTunaDomainResolvers preserves the legacy Cartesian pool only when the
// extension is absent. An explicit map must never silently fall back to it.
func (c *ClientConfig) PrepareTunaDomainResolvers() error {
	if c.TunaDomainResolvers == nil {
		return nil
	}
	if len(c.TunaDomainResolvers) > 16 {
		return fmt.Errorf("too many domain resolver selections")
	}
	domains := make(map[string]bool, len(c.Domains))
	for _, domain := range c.Domains {
		domains[domain] = true
	}
	selected := make(map[string][]string, len(c.TunaDomainResolvers))
	resolvers := make([]ResolverAddress, 0)
	seen := make(map[netip.AddrPort]bool)
	for domain, values := range c.TunaDomainResolvers {
		if !domains[domain] || len(values) > 128 {
			return fmt.Errorf("invalid domain resolver selection")
		}
		selected[domain] = []string{}
		localSeen := make(map[netip.AddrPort]bool)
		for _, value := range values {
			endpoint, err := tunaResolverEndpoint(value)
			if err != nil {
				return err
			}
			if !localSeen[endpoint] {
				selected[domain] = append(selected[domain], endpoint.String())
				localSeen[endpoint] = true
			}
			if !seen[endpoint] {
				resolvers = append(resolvers, ResolverAddress{IP: endpoint.Addr().String(), Port: int(endpoint.Port())})
				seen[endpoint] = true
			}
		}
	}
	if len(resolvers) == 0 {
		return fmt.Errorf("no active domain resolver pairs")
	}
	c.TunaDomainResolvers = selected
	c.Resolvers = resolvers // Preserve ports, including two endpoints on the same IP.
	return nil
}

func tunaResolverEndpoint(value string) (netip.AddrPort, error) {
	value = strings.TrimSpace(value)
	ip, err := netip.ParseAddr(strings.TrimSuffix(strings.TrimPrefix(value, "["), "]"))
	if err == nil {
		if ip.Zone() != "" {
			return netip.AddrPort{}, fmt.Errorf("scoped resolver rejected")
		}
		return netip.AddrPortFrom(ip.Unmap(), 53), nil
	}
	endpoint, err := netip.ParseAddrPort(value)
	if err != nil || endpoint.Port() == 0 || endpoint.Addr().Zone() != "" {
		return netip.AddrPort{}, fmt.Errorf("invalid numeric resolver endpoint")
	}
	return netip.AddrPortFrom(endpoint.Addr().Unmap(), endpoint.Port()), nil
}

func (c ClientConfig) TunaResolverAllowed(domain string, resolver ResolverAddress) bool {
	if c.TunaDomainResolvers == nil {
		return true
	}
	ip, err := netip.ParseAddr(resolver.IP)
	if err != nil || resolver.Port < 1 || resolver.Port > 65535 {
		return false
	}
	endpoint, err := tunaResolverEndpoint(netip.AddrPortFrom(ip, uint16(resolver.Port)).String())
	if err != nil {
		return false
	}
	for _, allowed := range c.TunaDomainResolvers[domain] {
		if allowed == endpoint.String() {
			return true
		}
	}
	return false
}
