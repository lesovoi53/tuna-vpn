package dns

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	fdns "github.com/xtls/xray-core/features/dns"
)

func TestTunaEmergencyIsTerminalAndReturnsToPrimary(t *testing.T) {
	for _, mode := range []string{"auto", "priority"} {
		for _, count := range []int{1, 2, 6, 8} {
			t.Run(fmt.Sprintf("%s/%d", mode, count), func(t *testing.T) {
				calls := []int{}
				failed := true
				children := make([]Server, count)
				for i := range children {
					index := i
					children[i] = &tunaFakeDNS{query: func(context.Context) ([]net.IP, uint32, error) {
						calls = append(calls, index)
						if failed {
							return nil, 0, errors.New("offline")
						}
						return tunaAnswer(context.Background())
					}}
				}
				emergency := &tunaFakeDNS{query: func(ctx context.Context) ([]net.IP, uint32, error) {
					calls = append(calls, -1)
					return tunaAnswer(ctx)
				}}
				s := tunaFixture(mode, 1000, children...)
				s.spec.Emergency = "tuna-system://android"
				s.emergency = emergency
				if ips, _, err := s.QueryIP(context.Background(), "answer.invalid", fdns.IPOption{IPv4Enable: true}); err != nil || len(ips) != 1 {
					t.Fatalf("terminal answer: %v %v", ips, err)
				}
				if len(calls) != count+1 || calls[count] != -1 || s.lastReported != "TUNA_DNS_STATUS remote 0 emergency-system" {
					t.Fatalf("reserve reached early or telemetry wrong: %v %s", calls, s.lastReported)
				}
				failed = false
				if _, _, err := s.QueryIP(context.Background(), "recovered.invalid", fdns.IPOption{IPv6Enable: true}); err != nil {
					t.Fatal(err)
				}
				if emergency.calls.Load() != 1 || calls[len(calls)-1] == -1 {
					t.Fatal("emergency became preferred over recovered primary")
				}
			})
		}
	}
}

func TestTunaEmergencyNeverFollowsNegativeOrCancellation(t *testing.T) {
	for _, outcome := range []struct {
		name string
		err  error
	}{
		{"nxdomain", fdns.RCodeError(3)}, {"nodata", fdns.ErrEmptyResponse}, {"empty", nil}, {"cancelled", context.Canceled},
	} {
		t.Run(outcome.name, func(t *testing.T) {
			first := &tunaFakeDNS{query: func(context.Context) ([]net.IP, uint32, error) { return nil, 47, outcome.err }}
			fallback := &tunaFakeDNS{query: tunaAnswer}
			emergency := &tunaFakeDNS{query: tunaAnswer}
			s := tunaFixture("priority", 1000, first, fallback)
			s.emergency = emergency
			_, _, err := s.QueryIP(context.Background(), "missing.invalid", fdns.IPOption{IPv4Enable: true})
			if err != outcome.err || fallback.calls.Load() != 0 || emergency.calls.Load() != 0 || s.health[0].failures != 0 {
				t.Fatalf("terminal result triggered fallback or health penalty: %v", err)
			}
		})
	}
	ctx, cancel := context.WithCancel(context.Background())
	first := &tunaFakeDNS{query: func(context.Context) ([]net.IP, uint32, error) { cancel(); return nil, 0, errors.New("offline") }}
	reserve := &tunaFakeDNS{query: tunaAnswer}
	s := tunaFixture("priority", 1000, first)
	s.emergency = reserve
	if _, _, err := s.QueryIP(ctx, "cancel.invalid", fdns.IPOption{IPv4Enable: true}); !errors.Is(err, context.Canceled) || reserve.calls.Load() != 0 {
		t.Fatalf("cancelled session reached emergency: %v", err)
	}
}

func TestTunaEmergencySharesTotalBudgetAndPreservesManualRoute(t *testing.T) {
	for _, group := range []string{"remote", "domestic"} {
		primary := &tunaFakeDNS{query: func(ctx context.Context) ([]net.IP, uint32, error) { <-ctx.Done(); return nil, 0, ctx.Err() }}
		reserve := &tunaFakeDNS{query: func(ctx context.Context) ([]net.IP, uint32, error) {
			if session.InboundFromContext(ctx).Tag != "tuna-dns-"+group {
				t.Error("manual reserve changed route")
			}
			deadline, ok := ctx.Deadline()
			if !ok || time.Until(deadline) < 20*time.Millisecond {
				t.Error("no reserved time")
			}
			<-ctx.Done()
			return nil, 0, ctx.Err()
		}}
		s := tunaFixture("priority", 450, primary, primary, primary, primary, primary, primary, primary, primary)
		s.spec.Group = group
		s.emergency = reserve
		ctx := session.ContextWithInbound(context.Background(), &session.Inbound{Tag: "tuna-dns-" + group})
		start := time.Now()
		_, _, err := s.QueryIP(ctx, "timeout.invalid", fdns.IPOption{IPv4Enable: true})
		if !errors.Is(err, context.DeadlineExceeded) || reserve.calls.Load() != 1 || time.Since(start) > 750*time.Millisecond {
			t.Fatalf("reserve deadline or call count: %v %v %d", err, time.Since(start), reserve.calls.Load())
		}
	}
}

func TestTunaEmergencyFactoryDoesNotCreateProbe(t *testing.T) {
	spec := tunaPoolSpec{Group: "remote", Mode: "auto", TimeoutMs: 1000, Addresses: []string{"tcp://192.0.2.1:53"}, Emergency: "tuna-system://android"}
	data, _ := json.Marshal(spec)
	server, err := newTunaDNSServer(context.Background(), "tuna-dns://"+base64.RawURLEncoding.EncodeToString(data), nil, false, false, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	s := server.(*tunaDNSServer)
	if len(s.children) != 1 || len(s.probes) != 1 || len(s.health) != 1 || s.emergency == nil {
		t.Fatal("reserve entered normal pool/probe/health state")
	}
}
