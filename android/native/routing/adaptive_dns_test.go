package dns

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	fdns "github.com/xtls/xray-core/features/dns"
)

type tunaFakeDNS struct {
	calls atomic.Int32
	query func(context.Context) ([]net.IP, uint32, error)
}

func (*tunaFakeDNS) Name() string         { return "synthetic" }
func (*tunaFakeDNS) IsDisableCache() bool { return true }
func (s *tunaFakeDNS) QueryIP(ctx context.Context, _ string, _ fdns.IPOption) ([]net.IP, uint32, error) {
	s.calls.Add(1)
	return s.query(ctx)
}
func tunaAnswer(context.Context) ([]net.IP, uint32, error) {
	return []net.IP{{192, 0, 2, 42}}, 60, nil
}
func tunaFixture(mode string, timeout int, children ...Server) *tunaDNSServer {
	return &tunaDNSServer{spec: tunaPoolSpec{Group: "remote", Mode: mode, TimeoutMs: timeout}, children: children, probes: children, health: make([]tunaHealth, len(children)), now: time.Now, lastProbe: time.Now()}
}

func TestTunaAdaptiveBudgetAndFailover(t *testing.T) {
	stuck := &tunaFakeDNS{query: func(ctx context.Context) ([]net.IP, uint32, error) { <-ctx.Done(); return nil, 0, ctx.Err() }}
	healthy := &tunaFakeDNS{query: tunaAnswer}
	s := tunaFixture("priority", 100, stuck, healthy)
	begin := time.Now()
	ips, _, err := s.QueryIP(context.Background(), "test.invalid", fdns.IPOption{IPv4Enable: true})
	if err != nil || len(ips) != 1 || stuck.calls.Load() != 1 || healthy.calls.Load() != 1 {
		t.Fatalf("fallback: %v %v", ips, err)
	}
	if elapsed := time.Since(begin); elapsed > 200*time.Millisecond {
		t.Fatalf("multiplied budget: %v", elapsed)
	}
	all := tunaFixture("priority", 100, stuck, stuck, stuck, stuck, stuck, stuck, stuck, stuck)
	begin = time.Now()
	_, _, err = all.QueryIP(context.Background(), "test.invalid", fdns.IPOption{IPv4Enable: true})
	if err == nil || time.Since(begin) > 250*time.Millisecond {
		t.Fatalf("total deadline not enforced: %v", err)
	}
}

func TestTunaAdaptiveNoFanoutAndBoundedProbe(t *testing.T) {
	now := time.Unix(1000, 0)
	servers := []Server{}
	for i := 0; i < 8; i++ {
		servers = append(servers, &tunaFakeDNS{query: tunaAnswer})
	}
	s := tunaFixture("auto", 1000, servers...)
	s.now = func() time.Time { return now }
	s.lastProbe = now
	for i := 0; i < 20; i++ {
		s.QueryIP(context.Background(), "test.invalid", fdns.IPOption{IPv4Enable: true})
	}
	if servers[0].(*tunaFakeDNS).calls.Load() != 20 {
		t.Fatal("preferred resolver not stable")
	}
	for _, child := range servers[1:] {
		if child.(*tunaFakeDNS).calls.Load() != 0 {
			t.Fatal("normal query fanned out")
		}
	}
	now = now.Add(time.Minute)
	for i := 0; i < 20; i++ {
		s.QueryIP(context.Background(), "test.invalid", fdns.IPOption{IPv4Enable: true})
	}
	var probes int32
	for _, child := range servers[1:] {
		probes += child.(*tunaFakeDNS).calls.Load()
	}
	if probes != 1 {
		t.Fatalf("probe rate not bounded: %d", probes)
	}
}

func TestTunaAdaptiveHistoryHysteresisAndCooldown(t *testing.T) {
	s := tunaFixture("auto", 1000, &tunaFakeDNS{query: tunaAnswer}, &tunaFakeDNS{query: tunaAnswer})
	now := time.Unix(1000, 0)
	s.now = func() time.Time { return now }
	s.lastProbe = now
	s.observe(0, 100*time.Millisecond, true)
	s.observe(1, 10*time.Millisecond, true)
	if s.preferred != 0 {
		t.Fatal("one fast observation caused switch")
	}
	s.observe(1, 10*time.Millisecond, true)
	if s.preferred != 1 {
		t.Fatal("repeated meaningful improvement ignored")
	}
	s.observe(1, 0, false)
	order, _ := s.plan()
	if len(order) != 1 || order[0] != 0 {
		t.Fatalf("failed resolver not cooled down: %v", order)
	}
	now = now.Add(16 * time.Second)
	order, _ = s.plan()
	if len(order) != 2 {
		t.Fatal("resolver never recovered from cooldown")
	}
}

func TestTunaAdaptivePreservesGroupRouteAndNegativeAnswers(t *testing.T) {
	for _, group := range []string{"remote", "domestic"} {
		expected := "tuna-dns-" + group
		first := &tunaFakeDNS{query: func(ctx context.Context) ([]net.IP, uint32, error) {
			if session.InboundFromContext(ctx).Tag != expected {
				t.Error("query crossed routing pool")
			}
			return nil, 0, errors.New("offline")
		}}
		second := &tunaFakeDNS{query: func(ctx context.Context) ([]net.IP, uint32, error) {
			if session.InboundFromContext(ctx).Tag != expected {
				t.Error("fallback crossed routing pool")
			}
			return tunaAnswer(ctx)
		}}
		s := tunaFixture("priority", 1000, first, second)
		s.spec.Group = group
		ctx := session.ContextWithInbound(context.Background(), &session.Inbound{Tag: expected})
		if _, _, err := s.QueryIP(ctx, "test.invalid", fdns.IPOption{IPv4Enable: true}); err != nil {
			t.Fatal(err)
		}
	}
	negative := &tunaFakeDNS{query: func(context.Context) ([]net.IP, uint32, error) { return nil, 60, fdns.RCodeError(3) }}
	fallback := &tunaFakeDNS{query: tunaAnswer}
	s := tunaFixture("priority", 1000, negative, fallback)
	if _, _, err := s.QueryIP(context.Background(), "missing.invalid", fdns.IPOption{IPv4Enable: true}); fdns.RCodeFromError(err) != 3 {
		t.Fatal("negative answer changed")
	}
	if fallback.calls.Load() != 0 || s.health[0].failures != 0 {
		t.Fatal("negative answer treated as resolver failure")
	}
}

func TestTunaAdaptiveCancellationDoesNotPunishResolver(t *testing.T) {
	child := &tunaFakeDNS{query: tunaAnswer}
	s := tunaFixture("auto", 1000, child)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, _, err := s.QueryIP(ctx, "test.invalid", fdns.IPOption{IPv4Enable: true})
	if !errors.Is(err, context.Canceled) || child.calls.Load() != 0 || s.health[0].failures != 0 {
		t.Fatal("cancelled request performed work")
	}
}

func TestTunaAdaptiveRejectsWrongPoolAndBlockedTags(t *testing.T) {
	child := &tunaFakeDNS{query: tunaAnswer}
	s := tunaFixture("auto", 1000, child)
	for _, tag := range []string{"tuna-dns-domestic", "tuna-dns-block"} {
		ctx := session.ContextWithInbound(context.Background(), &session.Inbound{Tag: tag})
		if _, _, err := s.QueryIP(ctx, "test.invalid", fdns.IPOption{IPv4Enable: true}); err == nil {
			t.Fatal("wrong tag accepted")
		}
	}
	if child.calls.Load() != 0 {
		t.Fatal("wrong pool or blocked query reached child/cache")
	}
}

func TestTunaAdaptiveTelemetryTracksAnswerWithoutQueryOrEndpoint(t *testing.T) {
	failed := &tunaFakeDNS{query: func(context.Context) ([]net.IP, uint32, error) { return nil, 0, errors.New("offline") }}
	s := tunaFixture("auto", 1000, failed, &tunaFakeDNS{query: tunaAnswer})
	_, _, err := s.QueryIP(context.Background(), "private-query.invalid", fdns.IPOption{IPv4Enable: true})
	if err != nil || s.lastReported != "TUNA_DNS_STATUS remote 2 fallback" {
		t.Fatalf("missing fallback observation: %q %v", s.lastReported, err)
	}
	_, _, err = s.QueryIP(context.Background(), "other-private.invalid", fdns.IPOption{IPv4Enable: true})
	if err != nil || s.lastReported != "TUNA_DNS_STATUS remote 2 selected" {
		t.Fatalf("missing reused winner: %q %v", s.lastReported, err)
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	s.QueryIP(cancelled, "cancelled.invalid", fdns.IPOption{IPv4Enable: true})
	if s.lastReported != "TUNA_DNS_STATUS remote 2 selected" {
		t.Fatal("cancelled request changed telemetry")
	}
}

func TestTunaLegacyObservationPreservesChildSemantics(t *testing.T) {
	for _, group := range []string{"remote", "domestic"} {
		for _, result := range []struct {
			name   string
			ips    []net.IP
			err    error
			status string
		}{
			{"success", []net.IP{{192, 0, 2, 42}}, nil, "selected"},
			{"nxdomain", nil, fdns.RCodeError(3), "negative"},
			{"nodata", nil, fdns.ErrEmptyResponse, "negative"},
			{"transport", nil, errors.New("offline"), ""},
			{"empty", nil, nil, ""},
		} {
			t.Run(group+"/"+result.name, func(t *testing.T) {
				ctx := session.ContextWithInbound(context.Background(), &session.Inbound{Tag: "tuna-dns-" + group})
				child := &tunaFakeDNS{query: func(got context.Context) ([]net.IP, uint32, error) {
					if got != ctx {
						t.Fatal("legacy context/deadline replaced")
					}
					return result.ips, 47, result.err
				}}
				s := tunaFixture("legacy", 0, child)
				s.spec.Group = group
				ips, ttl, err := s.QueryIP(ctx, "private-query.invalid", fdns.IPOption{IPv4Enable: true})
				if len(ips) != len(result.ips) || ttl != 47 || err != result.err || child.calls.Load() != 1 {
					t.Fatal("legacy result or call count changed")
				}
				want := ""
				if result.status != "" {
					want = "TUNA_DNS_STATUS " + group + " 1 " + result.status
				}
				if s.lastReported != want {
					t.Fatalf("observation %q, want %q", s.lastReported, want)
				}
			})
		}
	}
}

func TestTunaLegacyCancelledAndBlockedDoNotReport(t *testing.T) {
	for _, tag := range []string{"tuna-dns-block", "tuna-dns-domestic", "tuna-dns-remote"} {
		ctx, cancel := context.WithCancel(session.ContextWithInbound(context.Background(), &session.Inbound{Tag: tag}))
		if tag == "tuna-dns-remote" {
			cancel()
		}
		child := &tunaFakeDNS{query: tunaAnswer}
		s := tunaFixture("legacy", 0, child)
		s.QueryIP(ctx, "private-query.invalid", fdns.IPOption{IPv4Enable: true})
		cancel()
		if s.lastReported != "" || child.calls.Load() != 1 {
			t.Fatal("legacy observation changed child dispatch or reported wrong context")
		}
	}
}

func TestTunaLegacyFactoryKeepsIndependentCachesWithoutProbes(t *testing.T) {
	ctx := tunaPoolContext(context.Background())
	spec := tunaPoolSpec{Group: "remote", Mode: "legacy", Addresses: []string{"tcp://192.0.2.7:53"}}
	encoded, err := json.Marshal(spec)
	if err != nil {
		t.Fatal(err)
	}
	address := "tuna-dns://" + base64.RawURLEncoding.EncodeToString(encoded)
	first, err := newTunaDNSServer(ctx, address, nil, false, false, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	second, err := newTunaDNSServer(ctx, address, nil, false, false, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	a, b := first.(*tunaDNSServer), second.(*tunaDNSServer)
	if a == b || a.children[0] == b.children[0] || len(a.probes) != 0 || len(b.probes) != 0 || a.IsDisableCache() {
		t.Fatal("legacy cache ownership or probe construction changed")
	}
	if a.Name() != a.children[0].Name() {
		t.Fatal("legacy child name changed")
	}
	if len(ctx.Value(tunaPoolsKey{}).(*tunaPools).items) != 0 {
		t.Fatal("legacy observer entered shared pool registry")
	}
	for _, invalid := range []tunaPoolSpec{
		{Group: "remote", Mode: "legacy", TimeoutMs: 5000, Addresses: spec.Addresses},
		{Group: "remote", Mode: "legacy", Addresses: []string{"tcp://192.0.2.7:53", "tcp://192.0.2.8:53"}},
	} {
		data, _ := json.Marshal(invalid)
		if _, err := newTunaDNSServer(ctx, "tuna-dns://"+base64.RawURLEncoding.EncodeToString(data), nil, false, false, 0, nil); err == nil {
			t.Fatal("invalid legacy observer accepted")
		}
	}
}
