package fallback

import (
	"context"
	"errors"
	"sort"
	"sync"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
)

// Each instance belongs to one routing pool. No global resolver or direct fallback.
// Exploration replaces one ordinary query once a minute: never broadcast a query.
type tunaDNSHealth struct {
	rtt       time.Duration
	blocked   time.Time
	successes int
}
type tunaDNSSelector struct {
	mu        sync.Mutex
	health    []tunaDNSHealth
	selected  int
	nextProbe int
	probeAt   time.Time
	now       func() time.Time
}

func (s *tunaDNSSelector) order() []int {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	order := make([]int, len(s.health))
	for i := range order {
		order[i] = i
	}
	sort.SliceStable(order, func(i, j int) bool {
		a, b := order[i], order[j]
		if a == b {
			return false
		}
		ac, bc := now.Before(s.health[a].blocked), now.Before(s.health[b].blocked)
		if ac != bc {
			return !ac
		}
		if a == s.selected {
			return true
		}
		if b == s.selected {
			return false
		}
		return s.health[a].rtt > 0 && (s.health[b].rtt == 0 || s.health[a].rtt < s.health[b].rtt)
	})
	if len(order) > 1 && !now.Before(s.probeAt) {
		s.probeAt = now.Add(time.Minute)
		for n := 0; n < len(order); n++ {
			candidate := s.nextProbe % len(order)
			s.nextProbe++
			if candidate == s.selected || now.Before(s.health[candidate].blocked) {
				continue
			}
			for p, v := range order {
				if v == candidate {
					copy(order[1:p+1], order[:p])
					order[0] = candidate
					break
				}
			}
			break
		}
	}
	return order
}
func (s *tunaDNSSelector) record(index int, elapsed time.Duration, failed bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	h := &s.health[index]
	if failed {
		h.blocked = s.now().Add(30 * time.Second)
		return
	}
	h.blocked = time.Time{}
	if h.successes < 2 {
		h.successes++
	}
	if h.rtt == 0 {
		h.rtt = elapsed
	} else {
		h.rtt = (h.rtt*3 + elapsed) / 4
	}
	current := s.health[s.selected]
	// A healthy current resolver is replaced only after a meaningful improvement.
	if s.now().Before(current.blocked) || current.rtt == 0 || (h.successes >= 2 && h.rtt+20*time.Millisecond < current.rtt*4/5) {
		s.selected = index
	}
}

func tunaAdaptiveStrategy(servers []adapter.DNSTransport, timeout time.Duration) ExchangeStrategy {
	s := &tunaDNSSelector{health: make([]tunaDNSHealth, len(servers)), now: time.Now, probeAt: time.Now().Add(time.Minute), nextProbe: 1}
	return func(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
		ctx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		order := s.order()
		var last error = errors.New("DNS pool is empty")
		for position, index := range order {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			attempt, stop := context.WithTimeout(ctx, perAttemptTimeout(ctx, len(order)-position))
			start := time.Now()
			response, err := servers[index].Exchange(attempt, message.Copy())
			stop()
			if err == nil && response == nil {
				err = errors.New("empty DNS response")
			}
			if err == nil {
				response, err = checkExchangeResponse(response, nil)
			}
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			s.record(index, time.Since(start), err != nil)
			if err == nil {
				return response, nil
			}
			last = err
		}
		return nil, last
	}
}
