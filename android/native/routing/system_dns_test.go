package dns

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	stdnet "net"
	"sync/atomic"
	"testing"
	"time"

	fdns "github.com/xtls/xray-core/features/dns"
	"golang.org/x/net/dns/dnsmessage"
)

func tunaSystemFixture(t *testing.T, reply func(*dnsmessage.Message)) (*tunaSystemDNSServer, *atomic.Int32) {
	t.Helper()
	calls := &atomic.Int32{}
	return &tunaSystemDNSServer{dial: func(ctx context.Context) (stdnet.Conn, error) {
		return dialTunaSystemDNS(ctx, "tuna-dns-test-session", func(ctx context.Context, socket string) (stdnet.Conn, error) {
			calls.Add(1)
			client, server := stdnet.Pipe()
			go func() {
				defer server.Close()
				server.SetDeadline(time.Now().Add(time.Second))
				var size [2]byte
				if _, err := io.ReadFull(server, size[:]); err != nil {
					return
				}
				if ms := binary.BigEndian.Uint16(size[:]); ms < 1 || ms > 5000 {
					t.Errorf("invalid bridge budget %d", ms)
					return
				}
				if _, err := io.ReadFull(server, size[:]); err != nil {
					return
				}
				query := make([]byte, binary.BigEndian.Uint16(size[:]))
				if _, err := io.ReadFull(server, query); err != nil {
					return
				}
				var message dnsmessage.Message
				if err := message.Unpack(query); err != nil {
					t.Error(err)
					return
				}
				message.Response = true
				reply(&message)
				data, err := message.Pack()
				if err != nil {
					t.Error(err)
					return
				}
				binary.BigEndian.PutUint16(size[:], uint16(len(data)))
				server.Write(append(size[:], data...))
			}()
			return client, nil
		})
	}}, calls
}

func TestTunaSystemDNSFramingAndBothFamilies(t *testing.T) {
	s, calls := tunaSystemFixture(t, func(m *dnsmessage.Message) {
		q := m.Questions[0]
		answer := dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: q.Name, Type: q.Type, Class: dnsmessage.ClassINET, TTL: 42}}
		if q.Type == dnsmessage.TypeA {
			answer.Body = &dnsmessage.AResource{A: [4]byte{192, 0, 2, 42}}
		} else {
			answer.Body = &dnsmessage.AAAAResource{AAAA: [16]byte{0x20, 1, 0xd, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 42}}
		}
		m.Answers = []dnsmessage.Resource{answer}
	})
	ips, ttl, err := s.QueryIP(context.Background(), "answer.invalid", fdns.IPOption{IPv4Enable: true, IPv6Enable: true})
	if err != nil || len(ips) != 2 || ips[0].String() != "192.0.2.42" || ips[1].String() != "2001:db8::2a" || ttl != 42 || calls.Load() != 2 {
		t.Fatalf("system answers/framing: %v %d %v (%d queries)", ips, ttl, err, calls.Load())
	}
}

func TestTunaSystemDNSNegativeAndMalformedResponses(t *testing.T) {
	for _, scenario := range []string{"nxdomain", "nodata", "servfail", "id", "question", "truncated"} {
		t.Run(scenario, func(t *testing.T) {
			s, calls := tunaSystemFixture(t, func(m *dnsmessage.Message) {
				switch scenario {
				case "nxdomain":
					m.RCode = dnsmessage.RCodeNameError
				case "servfail":
					m.RCode = dnsmessage.RCodeServerFailure
				case "id":
					m.ID++
				case "question":
					m.Questions = nil
				case "truncated":
					m.Truncated = true
				}
			})
			ips, _, err := s.QueryIP(context.Background(), "missing.invalid", fdns.IPOption{IPv4Enable: true, IPv6Enable: true})
			wantCalls := int32(1)
			if scenario == "nodata" {
				wantCalls = 2
			}
			if err == nil || len(ips) != 0 || calls.Load() != wantCalls {
				t.Fatalf("bad result: %v %v calls=%d", ips, err, calls.Load())
			}
			if scenario == "nxdomain" && fdns.RCodeFromError(err) != 3 {
				t.Fatal("NXDOMAIN lost")
			}
			if scenario == "nodata" && !errors.Is(err, fdns.ErrEmptyResponse) {
				t.Fatal("NODATA lost")
			}
		})
	}
}

func TestTunaSystemDNSNeverDialsInvalidOrCancelledBridge(t *testing.T) {
	dials := 0
	dial := func(context.Context, string) (stdnet.Conn, error) { dials++; return nil, errors.New("unexpected dial") }
	for _, socket := range []string{"", "tuna-dns-", "127.0.0.1:53", "tuna-dns-/tmp", "tuna-dns-\x00bad"} {
		if _, err := dialTunaSystemDNS(context.Background(), socket, dial); err == nil {
			t.Fatal("invalid socket accepted")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := dialTunaSystemDNS(ctx, "tuna-dns-valid", dial); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if dials != 0 {
		t.Fatal("invalid bridge or canceled request dialed")
	}
}

func TestTunaSystemDNSCancellationClosesBlockedStream(t *testing.T) {
	for _, phase := range []string{"dial", "header", "reply"} {
		t.Run(phase, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			ready := make(chan struct{})
			done := make(chan error, 1)
			go func() {
				conn, err := dialTunaSystemDNS(ctx, "tuna-dns-cancel", func(ctx context.Context, _ string) (stdnet.Conn, error) {
					if phase == "dial" {
						close(ready)
						<-ctx.Done()
						return nil, ctx.Err()
					}
					client, server := stdnet.Pipe()
					go func() {
						defer server.Close()
						server.SetDeadline(time.Now().Add(time.Second))
						if phase == "header" {
							close(ready)
							<-ctx.Done()
							return
						}
						var size [2]byte
						io.ReadFull(server, size[:])
						io.ReadFull(server, size[:])
						io.CopyN(io.Discard, server, int64(binary.BigEndian.Uint16(size[:])))
						close(ready)
						<-ctx.Done()
					}()
					return client, nil
				})
				if err == nil {
					_, err = tunaSystemExchange(conn, make([]byte, 12))
					conn.Close()
				}
				done <- err
			}()
			select {
			case <-ready:
			case <-time.After(time.Second):
				t.Fatal("query did not reach phase")
			}
			cancel()
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("cancellation returned success")
				}
			case <-time.After(250 * time.Millisecond):
				t.Fatal("cancellation left blocked stream")
			}
		})
	}
}

func TestTunaSystemDNSGoResolverUsesTCPFraming(t *testing.T) {
	server, calls := tunaSystemFixture(t, func(m *dnsmessage.Message) {
		q := m.Questions[0]
		m.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, 12}}}}
	})
	resolver := &stdnet.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (stdnet.Conn, error) { return server.dial(ctx) }}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	ips, err := resolver.LookupIP(ctx, "ip4", "bootstrap.invalid.")
	if err != nil || len(ips) != 1 || ips[0].String() != "192.0.2.12" || calls.Load() != 1 {
		t.Fatalf("Go resolver bridge: %v %v", ips, err)
	}
}
