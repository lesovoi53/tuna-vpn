package fallback

import (
	"context"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"sync"
	"testing"
	"time"
)

type tunaFakeDNS struct {
	adapter.DNSTransport
	calls int
	wait  bool
	code  int
}

func (f *tunaFakeDNS) Exchange(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	f.calls++
	if f.wait {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	r := new(mDNS.Msg)
	r.SetReply(m)
	r.Rcode = f.code
	return r, nil
}
func TestTunaAdaptiveNoBroadcast(t *testing.T) {
	a, b := &tunaFakeDNS{}, &tunaFakeDNS{}
	query := tunaAdaptiveStrategy([]adapter.DNSTransport{a, b}, time.Second)
	for n := 0; n < 10; n++ {
		if _, err := query(context.Background(), new(mDNS.Msg)); err != nil {
			t.Fatal(err)
		}
	}
	if a.calls != 10 || b.calls != 0 {
		t.Fatalf("broadcast: %d %d", a.calls, b.calls)
	}
}

func TestTunaPriorityCancelledBeforeExchange(t *testing.T) {
	a, b := &tunaFakeDNS{}, &tunaFakeDNS{}
	query := sequentialStrategy([]adapter.DNSTransport{a, b}, nil, time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := query(ctx, new(mDNS.Msg)); err != context.Canceled {
		t.Fatalf("expected cancellation: %v", err)
	}
	if a.calls+b.calls != 0 {
		t.Fatal("cancelled query reached DNS")
	}
}
func TestTunaAdaptiveFailoverRemembersAndIsolates(t *testing.T) {
	a, b, other := &tunaFakeDNS{code: mDNS.RcodeServerFailure}, &tunaFakeDNS{}, &tunaFakeDNS{}
	query := tunaAdaptiveStrategy([]adapter.DNSTransport{a, b}, time.Second)
	_ = tunaAdaptiveStrategy([]adapter.DNSTransport{other}, time.Second)
	for n := 0; n < 2; n++ {
		if _, err := query(context.Background(), new(mDNS.Msg)); err != nil {
			t.Fatal(err)
		}
	}
	if a.calls != 1 || b.calls != 2 || other.calls != 0 {
		t.Fatalf("bad isolation/history: %d %d %d", a.calls, b.calls, other.calls)
	}
}
func TestTunaAdaptiveTotalBudget(t *testing.T) {
	a, b := &tunaFakeDNS{wait: true}, &tunaFakeDNS{}
	query := tunaAdaptiveStrategy([]adapter.DNSTransport{a, b}, 100*time.Millisecond)
	start := time.Now()
	if _, err := query(context.Background(), new(mDNS.Msg)); err != nil {
		t.Fatal(err)
	}
	if time.Since(start) > 200*time.Millisecond || b.calls != 1 {
		t.Fatal("first resolver exhausted total budget")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	before := b.calls
	if _, err := query(ctx, new(mDNS.Msg)); err != context.Canceled || b.calls != before {
		t.Fatal("cancellation ignored")
	}
}
func TestTunaAdaptiveHysteresisAndProbe(t *testing.T) {
	now := time.Now()
	s := &tunaDNSSelector{health: make([]tunaDNSHealth, 3), now: func() time.Time { return now }, probeAt: now.Add(time.Minute), nextProbe: 1}
	s.record(0, 100*time.Millisecond, false)
	s.record(1, 90*time.Millisecond, false)
	if s.order()[0] != 0 {
		t.Fatal("minor difference switched")
	}
	s.record(2, 40*time.Millisecond, false)
	if s.order()[0] != 0 {
		t.Fatal("single sample switched resolver")
	}
	s.record(2, 40*time.Millisecond, false)
	if s.order()[0] != 2 {
		t.Fatal("faster resolver not selected")
	}
	now = now.Add(time.Minute)
	if s.order()[0] != 1 {
		t.Fatal("rotating probe absent")
	}
	if s.order()[0] != 2 {
		t.Fatal("probe repeated before interval")
	}
}
func TestTunaAdaptiveConcurrentSelection(t *testing.T) {
	s := &tunaDNSSelector{health: make([]tunaDNSHealth, 8), now: time.Now, probeAt: time.Now().Add(time.Minute)}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				s.order()
				s.record(index, time.Millisecond, false)
			}
		}(i)
	}
	wg.Wait()
}
