package tunnel

import (
	"context"
	"encoding/binary"
	"fmt"
	"github.com/hashicorp/yamux"
	"io"
	"net"
	"testing"
	"time"
)

func tunaPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer ln.Close()
	c, e := net.Dial("tcp", ln.Addr().String())
	if e != nil {
		t.Fatal(e)
	}
	s, e := ln.Accept()
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { c.Close(); s.Close() })
	c.SetDeadline(time.Now().Add(3 * time.Second))
	return c, s
}
func tunaRead(t *testing.T, c net.Conn, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	if _, e := io.ReadFull(c, b); e != nil {
		t.Fatal(e)
	}
	return b
}
func tunaAuth(t *testing.T, c net.Conn) {
	t.Helper()
	c.Write([]byte{5, 1, 2})
	if tunaRead(t, c, 2)[1] != 2 {
		t.Fatal("method")
	}
	c.Write([]byte{1, 1, 'u', 1, 'p'})
	if tunaRead(t, c, 2)[1] != 0 {
		t.Fatal("auth")
	}
}
func TestTunaSocksAuthentication(t *testing.T) {
	for _, tc := range []struct {
		name        string
		hello, auth []byte
		success     bool
	}{
		{"noauth", []byte{5, 1, 0}, nil, false}, {"wrong-password", []byte{5, 1, 2}, []byte{1, 1, 'u', 1, 'x'}, false},
		{"bad-version", []byte{5, 1, 2}, []byte{2, 1, 'u'}, false}, {"valid", []byte{5, 1, 2}, []byte{1, 1, 'u', 1, 'p'}, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c, s := tunaPair(t)
			done := make(chan error, 1)
			go func() { defer s.Close(); _, _, _, e := socks5Handshake(s, "u", "p"); done <- e }()
			c.Write(tc.hello)
			r := tunaRead(t, c, 2)
			if tc.auth != nil {
				if r[1] != 2 {
					t.Fatal("auth not required")
				}
				c.Write(tc.auth)
				r = tunaRead(t, c, 2)
				if tc.success && r[1] != 0 {
					t.Fatal("auth failed")
				}
			} else if r[1] != 255 {
				t.Fatal("accepted noauth")
			}
			if tc.success {
				c.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 0, 80})
			}
			if e := <-done; (e == nil) != tc.success {
				t.Fatalf("unexpected result: %v", e)
			}
		})
	}
}
func TestTunaUDPOnlyDNSAndBoundPeer(t *testing.T) {
	a, b := net.Pipe()
	yc, _ := yamux.Client(a, nil)
	ys, _ := yamux.Server(b, nil)
	defer yc.Close()
	defer ys.Close()
	hits := make(chan uint16, 8)
	go func() {
		for {
			st, e := ys.Accept()
			if e != nil {
				return
			}
			go func() {
				defer st.Close()
				_, port, e := readStreamTarget(st)
				if e != nil {
					return
				}
				hits <- port
				var h [2]byte
				if _, e = io.ReadFull(st, h[:]); e != nil {
					return
				}
				q := make([]byte, binary.BigEndian.Uint16(h[:]))
				if _, e = io.ReadFull(st, q); e != nil {
					return
				}
				st.Write(append(h[:], q...))
			}()
		}
	}()
	c, s := tunaPair(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go proxyStream(ctx, yc, s, nil, "u", "p")
	udp, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if e != nil {
		t.Fatal(e)
	}
	defer udp.Close()
	tunaAuth(t, c)
	port := udp.LocalAddr().(*net.UDPAddr).Port
	c.Write([]byte{5, 3, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	r := tunaRead(t, c, 10)
	relay := &net.UDPAddr{IP: net.IP(r[4:8]), Port: int(binary.BigEndian.Uint16(r[8:10]))}
	pkt := []byte{0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 0x12, 0x34}
	bad := append([]byte{}, pkt...)
	bad[8], bad[9] = 1, 187 // QUIC/443
	udp.WriteToUDP(bad, relay)
	other, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	defer other.Close()
	other.WriteToUDP(pkt, relay)
	select {
	case <-hits:
		t.Fatal("forwarded forbidden UDP")
	case <-time.After(100 * time.Millisecond):
	}
	udp.WriteToUDP(pkt, relay)
	udp.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 64)
	n, _, e := udp.ReadFromUDP(buf)
	if e != nil || n != len(pkt) {
		t.Fatalf("DNS reply n=%d err=%v", n, e)
	}
	if <-hits != 53 {
		t.Fatal("wrong target")
	}
	c.Close()
	cancel()
}
func TestTunaBootstrapTransportAndInvalidOverride(t *testing.T) {
	for _, network := range []string{"tcp", "udp"} {
		t.Run(network, func(t *testing.T) {
			var addr string
			var stop func()
			if network == "tcp" {
				ln, e := net.Listen("tcp", "127.0.0.1:0")
				if e != nil {
					t.Fatal(e)
				}
				addr = ln.Addr().String()
				stop = func() { ln.Close() }
				go func() {
					c, e := ln.Accept()
					if e == nil {
						c.Close()
					}
				}()
			} else {
				ln, e := net.ListenPacket("udp", "127.0.0.1:0")
				if e != nil {
					t.Fatal(e)
				}
				addr = ln.LocalAddr().String()
				stop = func() { ln.Close() }
			}
			defer stop()
			r := tunaStorageResolver(network + "://" + addr)
			c, e := r.Dial(context.Background(), "udp", "ignored:53")
			if e != nil {
				t.Fatal(e)
			}
			defer c.Close()
			if c.RemoteAddr().Network() != network {
				t.Fatal("wrong DNS transport")
			}
		})
	}
	for _, bad := range []string{"tcp://dns.invalid:53", "https://127.0.0.1:53", "udp://127.0.0.1:0"} {
		if _, e := tunaStorageResolver(bad).Dial(context.Background(), "udp", "ignored"); e == nil {
			t.Fatal("invalid override accepted")
		}
	}
}

func TestTunaSystemBootstrapFailsClosed(t *testing.T) {
	t.Setenv("TUNA_SYSTEM_DNS_SOCKET", "")
	if _, err := tunaStorageResolver("system").Dial(context.Background(), "udp", "127.0.0.1:53"); err == nil {
		t.Fatal("missing bridge accepted")
	}
	for _, socket := range []string{"", "tuna-dns-", "/tmp/socket", "tuna-dns-x/y", "tuna-dns-x\x00"} {
		called := false
		_, err := tunaDialSystem(context.Background(), socket, func(context.Context, string) (net.Conn, error) { called = true; return nil, nil })
		if err == nil || called {
			t.Fatalf("invalid socket reached dial: %q", socket)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	called := false
	if _, err := tunaDialSystem(ctx, "tuna-dns-test", func(context.Context, string) (net.Conn, error) { called = true; return nil, nil }); err != context.Canceled || called {
		t.Fatal("cancelled query reached dial")
	}
}

func TestTunaSystemBootstrapUsesTcpDnsFraming(t *testing.T) {
	result := make(chan error, 1)
	resolver := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return tunaDialSystem(ctx, "tuna-dns-test", func(context.Context, string) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer server.Close()
				var header [2]byte
				if _, err := io.ReadFull(server, header[:]); err != nil {
					result <- err
					return
				}
				timeout := binary.BigEndian.Uint16(header[:])
				if timeout < 1 || timeout > 5000 {
					result <- fmt.Errorf("bad timeout %d", timeout)
					return
				}
				if _, err := io.ReadFull(server, header[:]); err != nil {
					result <- err
					return
				}
				q := make([]byte, binary.BigEndian.Uint16(header[:]))
				if _, err := io.ReadFull(server, q); err != nil {
					result <- err
					return
				}
				end := 12
				for q[end] != 0 {
					end += int(q[end]) + 1
				}
				end += 5
				answer := append([]byte(nil), q[:end]...)
				answer[2], answer[3] = 0x81, 0x80
				answer[6], answer[7] = 0, 1
				answer[10], answer[11] = 0, 0
				answer = append(answer, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 192, 0, 2, 53)
				binary.BigEndian.PutUint16(header[:], uint16(len(answer)))
				_, err := server.Write(append(header[:], answer...))
				result <- err
			}()
			return client, nil
		})
	}}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	addresses, err := resolver.LookupIP(ctx, "ip4", "example.test.")
	if err != nil || len(addresses) != 1 || !addresses[0].Equal(net.IPv4(192, 0, 2, 53)) {
		t.Fatalf("lookup %v %v", addresses, err)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}

func TestTunaSystemBootstrapCancellationAndDeadlineCloseStream(t *testing.T) {
	for _, deadline := range []bool{false, true} {
		t.Run(fmt.Sprint(deadline), func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if deadline {
				var timeoutCancel context.CancelFunc
				ctx, timeoutCancel = context.WithTimeout(ctx, 40*time.Millisecond)
				defer timeoutCancel()
			}
			disconnected := make(chan error, 1)
			stream, err := tunaDialSystem(ctx, "tuna-dns-test", func(context.Context, string) (net.Conn, error) {
				client, server := net.Pipe()
				go func() {
					defer server.Close()
					var header [2]byte
					if _, err := io.ReadFull(server, header[:]); err != nil {
						disconnected <- err
						return
					}
					_, err := server.Read(header[:])
					disconnected <- err
				}()
				return client, nil
			})
			if err != nil {
				t.Fatal(err)
			}
			defer stream.Close()
			if _, packet := stream.(net.PacketConn); packet {
				t.Fatal("resolver would select UDP framing")
			}
			if !deadline {
				cancel()
			}
			select {
			case err := <-disconnected:
				if err == nil {
					t.Fatal("stream left open")
				}
			case <-time.After(time.Second):
				t.Fatal("cancellation did not close stream")
			}
		})
	}
}
