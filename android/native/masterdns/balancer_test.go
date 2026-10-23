package client

import (
	"fmt"
	"testing"
	"time"

	Enums "masterdnsvpn-go/internal/enums"
)

func tunaSpeedBalancer(t *testing.T, domains ...string) (*Balancer, []string) {
	t.Helper()
	b := NewBalancer(BalancingLossThenLatency, nil)
	var connections []*Connection
	var keys []string
	for _, domain := range domains {
		key := "192.0.2.1|53|" + domain
		keys = append(keys, key)
		connections = append(connections, &Connection{
			Key: key, Domain: domain, Resolver: "192.0.2.1", ResolverPort: 53,
		})
	}
	b.SetConnections(connections)
	for _, key := range keys {
		if !b.SetConnectionValidity(key, true) {
			t.Fatal("activate", key)
		}
		for i := 0; i < 10; i++ {
			b.ReportSend(key)
			b.ReportSuccess(key, 10*time.Millisecond)
		}
	}
	return b, keys
}

func tunaSelect(t *testing.T, b *Balancer, packet uint8, count int) []Connection {
	t.Helper()
	targets, err := b.SelectTargets(packet, 42, count)
	if err != nil || len(targets) == 0 {
		t.Fatalf("SelectTargets: %v, %v", targets, err)
	}
	seen := map[string]bool{}
	for _, target := range targets {
		if !target.IsValid || seen[target.Key] {
			t.Fatalf("invalid or duplicate target: %+v", targets)
		}
		seen[target.Key] = true
	}
	return targets
}

// Exercise the packet scheduling entry point, including an existing stream route:
// duplication=1 must distribute data across eligible resolver/domain pairs.
func TestTunaSpeedSelectTargetsRotatesEqualDomains(t *testing.T) {
	for _, packet := range []uint8{Enums.PACKET_STREAM_DATA, Enums.PACKET_STREAM_RESEND} {
		t.Run(fmt.Sprint(packet), func(t *testing.T) {
			b, keys := tunaSpeedBalancer(t, "a.example.invalid", "b.example.invalid", "c.example.invalid")
			if pool := b.lossThenLatencyCandidatesLocked(""); len(pool) != len(keys) {
				t.Fatalf("quality filter unexpectedly excluded equal peers: %v", pool)
			}
			b.EnsureStream(42)
			counts := map[string]int{}
			for i := 0; i < 12; i++ {
				targets := tunaSelect(t, b, packet, 1)
				if len(targets) != 1 {
					t.Fatalf("duplication=1 returned %d targets", len(targets))
				}
				counts[targets[0].Key]++
			}
			for _, key := range keys {
				if counts[key] != 4 {
					t.Fatalf("equal eligible domains should each receive 4/12 packets: %v", counts)
				}
			}
		})
	}
}

func TestTunaSpeedSelectTargetsWithoutStreamState(t *testing.T) {
	b, keys := tunaSpeedBalancer(t, "a.example.invalid", "b.example.invalid")
	first := tunaSelect(t, b, Enums.PACKET_STREAM_DATA, 1)[0].Key
	second := tunaSelect(t, b, Enums.PACKET_STREAM_DATA, 1)[0].Key
	if first == second {
		t.Fatalf("no sticky stream exists, but both packets chose %s from %v", first, keys)
	}
}

func TestTunaSpeedSelectTargetsQualityAndAvailability(t *testing.T) {
	for _, degradation := range []string{"loss", "latency", "inactive"} {
		t.Run(degradation, func(t *testing.T) {
			b, keys := tunaSpeedBalancer(t, "a.example.invalid", "b.example.invalid", "c.example.invalid")
			// Start with all peers eligible, then degrade the next scheduled peer.
			_ = tunaSelect(t, b, Enums.PACKET_STREAM_DATA, 1)
			switch degradation {
			case "loss":
				b.ReportSend(keys[1])
				b.ReportTimeout(keys[1], time.Unix(100, 0), time.Second, 1)
			case "latency":
				b.ReportSend(keys[1])
				b.ReportSuccess(keys[1], time.Second)
			case "inactive":
				b.SetConnectionValidity(keys[1], false)
			}
			counts := map[string]int{}
			for i := 0; i < 10; i++ {
				counts[tunaSelect(t, b, Enums.PACKET_STREAM_DATA, 1)[0].Key]++
			}
			if counts[keys[0]] != 5 || counts[keys[2]] != 5 || counts[keys[1]] != 0 {
				t.Fatalf("only healthy shortlisted peers must rotate: %v", counts)
			}
		})
	}
}

func TestTunaSpeedSelectTargetsSingleAndNoEligible(t *testing.T) {
	b, keys := tunaSpeedBalancer(t, "a.example.invalid", "b.example.invalid")
	b.SetConnectionValidity(keys[1], false)
	for i := 0; i < 10; i++ {
		if got := tunaSelect(t, b, Enums.PACKET_STREAM_DATA, 1); len(got) != 1 || got[0].Key != keys[0] {
			t.Fatalf("single available peer: %v", got)
		}
	}
	b.SetConnectionValidity(keys[0], false)
	if targets, err := b.SelectTargets(Enums.PACKET_STREAM_DATA, 42, 1); len(targets) != 0 || err != ErrNoValidConnections {
		t.Fatalf("no available peers: %v, %v", targets, err)
	}
}

func TestTunaSpeedSelectTargetsDuplicatedStreamKeepsPreferred(t *testing.T) {
	for _, count := range []int{2, 3} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			b, keys := tunaSpeedBalancer(t, "a.example.invalid", "b.example.invalid", "c.example.invalid")
			b.EnsureStream(42)
			b.streamRoutes[42].PreferredResolverKey = keys[2]
			for i := 0; i < 10; i++ {
				got := tunaSelect(t, b, Enums.PACKET_STREAM_DATA, count)
				if len(got) != count || got[0].Key != keys[2] {
					t.Fatalf("duplication=%d must retain preferred and unique copies: %v", count, got)
				}
			}
		})
	}
}
