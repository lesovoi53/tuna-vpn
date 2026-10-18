package dns

// TUNA's bounded resolver pool. Primary and manual emergency queries retain the
// calling client's inbound tag. Explicit system emergency uses Android's bridge.
import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	fdns "github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/features/routing"
)

type tunaPoolSpec struct {
	Group     string   `json:"group"`
	Mode      string   `json:"mode"`
	TimeoutMs int      `json:"timeoutMs"`
	Addresses []string `json:"addresses"`
	Emergency string   `json:"emergency,omitempty"`
}
type tunaPoolsKey struct{}
type tunaPools struct {
	sync.Mutex
	items map[string]*tunaDNSServer
}

func tunaPoolContext(ctx context.Context) context.Context {
	return context.WithValue(ctx, tunaPoolsKey{}, &tunaPools{items: make(map[string]*tunaDNSServer)})
}

type tunaHealth struct {
	latency  time.Duration
	failures int
	retryAt  time.Time
	wins     int
}
type tunaDNSServer struct {
	spec         tunaPoolSpec
	children     []Server
	emergency    Server
	probes       []Server
	cacheOff     bool
	now          func() time.Time
	mu           sync.Mutex
	health       []tunaHealth
	preferred    int
	cursor       int
	lastProbe    time.Time
	lastReported string
}

func (s *tunaDNSServer) Name() string {
	if s.spec.Mode == "legacy" {
		return s.children[0].Name()
	}
	return "TUNA-DNS-" + s.spec.Group
}
func (s *tunaDNSServer) IsDisableCache() bool { return s.cacheOff }

// Transition-only telemetry. Never print queried domains, endpoint URLs or keys.
// A single pool can answer concurrent questions: this is the last observed answer,
// not a promise that every in-flight question used the same resolver.
func (s *tunaDNSServer) report(index int, reason string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	line := fmt.Sprintf("TUNA_DNS_STATUS %s %d %s", s.spec.Group, index+1, reason)
	if line != s.lastReported {
		s.lastReported = line
		fmt.Println(line)
	}
}

func newTunaDNSServer(ctx context.Context, encoded string, dispatcher routing.Dispatcher, disableCache, serveStale bool, ttl uint32, clientIP net.IP) (Server, error) {
	data, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(encoded, "tuna-dns://"))
	if err != nil || len(data) > 16384 {
		return nil, fmt.Errorf("invalid DNS pool encoding")
	}
	var spec tunaPoolSpec
	if json.Unmarshal(data, &spec) != nil || (spec.Group != "remote" && spec.Group != "domestic") || len(spec.Addresses) < 1 || len(spec.Addresses) > 8 {
		return nil, fmt.Errorf("invalid DNS pool")
	}
	legacy := spec.Mode == "legacy"
	if (legacy && (len(spec.Addresses) != 1 || spec.TimeoutMs != 0 || spec.Emergency != "")) || (!legacy && ((spec.Mode != "auto" && spec.Mode != "priority") || spec.TimeoutMs < 1000 || spec.TimeoutMs > 10000)) {
		return nil, fmt.Errorf("invalid DNS pool")
	}
	registry, _ := ctx.Value(tunaPoolsKey{}).(*tunaPools)
	// Legacy clients historically own separate caches, including BLOCK clients.
	// Observe their existing child without sharing state or creating probe clients.
	if legacy {
		registry = nil
	}
	if registry != nil {
		registry.Lock()
		defer registry.Unlock()
		if old := registry.items[encoded]; old != nil {
			return old, nil
		}
	}
	s := &tunaDNSServer{spec: spec, cacheOff: disableCache, now: time.Now, health: make([]tunaHealth, len(spec.Addresses)), lastProbe: time.Now()}
	seen := map[string]bool{}
	addresses := append([]string(nil), spec.Addresses...)
	if spec.Emergency != "" {
		addresses = append(addresses, spec.Emergency)
	}
	for index, address := range addresses {
		isEmergency := index == len(spec.Addresses)
		if seen[address] && !isEmergency {
			return nil, fmt.Errorf("duplicate DNS pool endpoint")
		}
		seen[address] = true
		dest := net.Destination{Network: net.Network_UDP, Address: net.ParseAddress(address), Port: 53}
		if address == "tuna-system://android" && isEmergency {
			// Android's authenticated, network-bound bridge, never libc/localhost.
		} else if strings.HasPrefix(address, "tuna-doh://") {
			// The dedicated factory validates the encoded endpoint and bootstrap.
		} else if strings.Contains(address, "://") {
			u, e := url.Parse(address)
			if e != nil || (u.Scheme != "https" && u.Scheme != "tcp") || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
				return nil, fmt.Errorf("unsupported DNS pool endpoint")
			}
		} else {
			parsed, e := net.ParseDestination("udp:" + address)
			if e != nil || !parsed.Address.Family().IsIP() {
				return nil, fmt.Errorf("invalid UDP resolver")
			}
			dest = parsed
			if dest.Port == 0 {
				dest.Port = 53
			}
		}
		child, e := NewServer(ctx, dest, dispatcher, disableCache, serveStale, ttl, clientIP)
		if e != nil {
			return nil, e
		}
		if isEmergency {
			s.emergency = child
			continue
		}
		s.children = append(s.children, child)
		if legacy {
			continue
		}
		probe, e := NewServer(ctx, dest, dispatcher, true, false, 0, clientIP)
		if e != nil {
			return nil, e
		}
		s.probes = append(s.probes, probe)
	}
	if registry != nil {
		registry.items[encoded] = s
	}
	return s, nil
}

// Return a stable order and at most one rotating health probe per minute.
// Probes reuse the next real question (no idle timers or synthetic DNS traffic).
func (s *tunaDNSServer) plan() ([]int, int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	order := make([]int, 0, len(s.children))
	for i, h := range s.health {
		if !now.Before(h.retryAt) {
			order = append(order, i)
		}
	}
	if len(order) == 0 {
		best := 0
		for i := range s.health {
			if s.health[i].retryAt.Before(s.health[best].retryAt) {
				best = i
			}
		}
		return []int{best}, -1
	}
	if s.spec.Mode == "auto" {
		sort.SliceStable(order, func(a, b int) bool {
			i, j := order[a], order[b]
			if i == j {
				return false
			}
			if i == s.preferred {
				return true
			}
			if j == s.preferred {
				return false
			}
			x, y := s.health[i].latency, s.health[j].latency
			if x == 0 {
				return false
			}
			if y == 0 {
				return true
			}
			return x < y
		})
		if now.Sub(s.lastProbe) >= time.Minute && len(s.children) > 1 {
			for n := 0; n < len(s.children); n++ {
				s.cursor = (s.cursor + 1) % len(s.children)
				probe := s.cursor
				if probe == s.preferred || now.Before(s.health[probe].retryAt) {
					continue
				}
				s.lastProbe = now
				chosen := []int{probe}
				for _, i := range order {
					if i != probe {
						chosen = append(chosen, i)
					}
				}
				return chosen, probe
			}
		}
	}
	return order, -1
}
func (s *tunaDNSServer) observe(index int, latency time.Duration, success bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	h := &s.health[index]
	if !success {
		h.failures++
		h.wins = 0
		h.retryAt = s.now().Add(time.Duration(1<<min(h.failures-1, 4)) * 15 * time.Second)
		return
	}
	h.failures = 0
	h.retryAt = time.Time{}
	if h.latency == 0 {
		h.latency = latency
	} else {
		h.latency = (3*h.latency + latency) / 4
	}
	if index == s.preferred {
		return
	}
	current := s.health[s.preferred]
	if current.failures > 0 {
		s.preferred = index
		h.wins = 0
		return
	}
	// Two successful observations and a meaningful advantage prevent flapping.
	if current.latency > 0 && h.latency*5 < current.latency*4 && current.latency-h.latency >= 20*time.Millisecond {
		h.wins++
		if h.wins >= 2 {
			s.preferred = index
			h.wins = 0
		}
	} else {
		h.wins = 0
	}
}
func (s *tunaDNSServer) QueryIP(ctx context.Context, domain string, option fdns.IPOption) ([]net.IP, uint32, error) {
	if s.spec.Mode == "legacy" {
		// Observation only: preserve the caller's context, timeout, cache and the
		// child's exact result. BLOCK and other tags must not claim a pool answer.
		ips, ttl, err := s.children[0].QueryIP(ctx, domain, option)
		inbound := session.InboundFromContext(ctx)
		if ctx.Err() == nil && inbound != nil && inbound.Tag == "tuna-dns-"+s.spec.Group {
			if errors.Is(err, fdns.ErrEmptyResponse) || fdns.RCodeFromError(err) == 3 {
				s.report(0, "negative")
			} else if err == nil && len(ips) > 0 {
				s.report(0, "selected")
			}
		}
		return ips, ttl, err
	}
	if inbound := session.InboundFromContext(ctx); inbound != nil && inbound.Tag != "tuna-dns-"+s.spec.Group {
		return nil, 0, fmt.Errorf("DNS pool routing tag mismatch")
	}
	ctx, cancel := context.WithTimeout(ctx, time.Duration(s.spec.TimeoutMs)*time.Millisecond)
	defer cancel()
	order, probe := s.plan()
	var last error
	for position, index := range order {
		if err := ctx.Err(); err != nil {
			return nil, 0, err
		}
		deadline, _ := ctx.Deadline()
		remaining := time.Until(deadline)
		// The budget is TOTAL, not N multiplied timeouts. Reserve each untried
		// resolver a share so an unresponsive first server cannot consume it all.
		shares := len(order) - position
		if s.emergency != nil {
			shares++
		}
		attempt, cancelAttempt := context.WithTimeout(ctx, remaining/time.Duration(shares))
		begin := s.now()
		server := s.children[index]
		if index == probe {
			server = s.probes[index]
		}
		ips, ttl, err := server.QueryIP(attempt, domain, option)
		cancelAttempt()
		if ctx.Err() != nil {
			return nil, 0, ctx.Err()
		}
		// Negative DNS answers are not transport failures; do not punish the
		// resolver or leak the question to another resolver for a different answer.
		if errors.Is(err, fdns.ErrEmptyResponse) || fdns.RCodeFromError(err) == 3 || (err == nil && len(ips) == 0) {
			s.observe(index, s.now().Sub(begin), true)
			s.report(index, "negative")
			return ips, ttl, err
		}
		if errors.Is(err, context.Canceled) {
			return nil, 0, err
		}
		success := err == nil && len(ips) > 0
		s.observe(index, s.now().Sub(begin), success)
		if success {
			reason := "selected"
			if position > 0 {
				reason = "fallback"
			} else if index == probe {
				reason = "probe"
			}
			s.report(index, reason)
			return ips, ttl, nil
		}
		last = err
	}
	if s.emergency != nil {
		if err := ctx.Err(); err != nil {
			return nil, 0, err
		}
		ips, ttl, err := s.emergency.QueryIP(ctx, domain, option)
		if ctx.Err() != nil {
			return nil, 0, ctx.Err()
		}
		if errors.Is(err, context.Canceled) {
			return nil, 0, err
		}
		reason := "emergency-resolver"
		if s.spec.Emergency == "tuna-system://android" {
			reason = "emergency-system"
		}
		if errors.Is(err, fdns.ErrEmptyResponse) || fdns.RCodeFromError(err) == 3 || (err == nil && len(ips) == 0) {
			reason += "-negative"
		}
		if err == nil || errors.Is(err, fdns.ErrEmptyResponse) || fdns.RCodeFromError(err) == 3 {
			s.report(-1, reason)
		}
		return ips, ttl, err
	}
	if last == nil {
		last = fdns.ErrEmptyResponse
	}
	return nil, 0, last
}
