package snelllab

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-snell/snellv6"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// This fixture uses only loopback and a synthetic key. It probes transport reuse,
// not the Android VPN, TLS stack, or the behavior of a production Snell server.
func localV6Client(t *testing.T, reuse bool, mode snellv6.Mode) (*snellv6.Client, func(bool), func() int) {
	t.Helper()
	psk := []byte("local-test-key-with-no-production-value")
	service, err := snellv6.NewService(snellv6.ServerOptions{PSK: psk, Mode: mode, Handler: echoHandler{}})
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var connections []net.Conn
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			connections = append(connections, conn)
			mu.Unlock()
			go func() {
				if err := service.NewConnection(context.Background(), conn, M.Socksaddr{}, nil); err != nil {
					conn.Close()
				}
			}()
		}
	}()
	client, err := snellv6.NewClient(snellv6.ClientOptions{PSK: psk, Reuse: reuse, Mode: mode, Dialer: N.SystemDialer, Server: M.ParseSocksaddr(listener.Addr().String())})
	if err != nil {
		listener.Close()
		t.Fatal(err)
	}
	drop := func(reset bool) {
		mu.Lock()
		defer mu.Unlock()
		for _, conn := range connections {
			if reset {
				conn.(*net.TCPConn).SetLinger(0)
			}
			conn.Close()
		}
	}
	t.Cleanup(func() { client.Close(); listener.Close(); drop(false) })
	return client, drop, func() int { mu.Lock(); defer mu.Unlock(); return len(connections) }
}

func exchangeV6(client *snellv6.Client, size int, early bool) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := client.DialContext(ctx, M.ParseSocksaddr("echo.invalid:443"))
	if err != nil {
		return err
	}
	defer conn.Close()
	if err = conn.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		return err
	}
	want := bytes.Repeat([]byte{42}, size)
	written := make(chan error, 1)
	go func() {
		_, err := conn.Write(want)
		if err == nil && !early {
			if half, ok := conn.(interface{ CloseWrite() error }); ok {
				err = half.CloseWrite()
			}
		}
		written <- err
	}()
	readSize := size
	if early {
		readSize = min(size, 31)
	}
	got := make([]byte, readSize)
	if _, err = io.ReadFull(conn, got); err != nil {
		return err
	}
	if !bytes.Equal(got, want[:readSize]) {
		return fmt.Errorf("corrupt response")
	}
	if early {
		conn.Close()
	}
	if err = <-written; err != nil && !early {
		return err
	}
	if !early {
		var tail [1]byte
		n, err := conn.Read(tail[:])
		if n != 0 || err != io.EOF {
			return fmt.Errorf("frame EOF: n=%d err=%v", n, err)
		}
	}
	return nil
}

func TestSnellV6RepeatedEarlyClose(t *testing.T) {
	for _, mode := range []snellv6.Mode{snellv6.ModeDefault, snellv6.ModeUnshaped, snellv6.ModeUnsafeRaw} {
		for _, reuse := range []bool{false, true} {
			t.Run(fmt.Sprintf("mode=%s/reuse=%v", mode, reuse), func(t *testing.T) {
				client, _, _ := localV6Client(t, reuse, mode)
				for i := 0; i < 40; i++ {
					if err := exchangeV6(client, 65536, i%2 == 0); err != nil {
						t.Fatalf("round %d: %v", i, err)
					}
				}
			})
		}
	}
}

func TestSnellV6RemoteCloseOfIdleSocket(t *testing.T) {
	if runtime.GOOS != "linux" && runtime.GOOS != "android" {
		t.Skip("Android/Linux native socket check")
	}
	for _, mode := range []snellv6.Mode{snellv6.ModeDefault, snellv6.ModeUnshaped, snellv6.ModeUnsafeRaw} {
		for _, reuse := range []bool{false, true} {
			for _, reset := range []bool{false, true} {
				t.Run(fmt.Sprintf("mode=%s/reuse=%v/reset=%v", mode, reuse, reset), func(t *testing.T) {
					client, drop, count := localV6Client(t, reuse, mode)
					if err := exchangeV6(client, 1400, false); err != nil {
						t.Fatal(err)
					}
					drop(reset)
					time.Sleep(50 * time.Millisecond)
					if err := exchangeV6(client, 1400, false); err != nil {
						t.Fatalf("stream after idle close: %v", err)
					}
					if count() != 2 {
						t.Fatalf("expected one replacement socket, got %d", count())
					}
				})
			}
		}
	}
}

func TestSnellV6HealthySocketStillReused(t *testing.T) {
	for _, mode := range []snellv6.Mode{snellv6.ModeDefault, snellv6.ModeUnshaped, snellv6.ModeUnsafeRaw} {
		t.Run(mode.String(), func(t *testing.T) {
			client, _, count := localV6Client(t, true, mode)
			for i := 0; i < 40; i++ {
				if err := exchangeV6(client, 1400, false); err != nil {
					t.Fatalf("round %d: %v", i, err)
				}
			}
			if count() != 1 {
				t.Fatalf("healthy socket not reused: %d sockets", count())
			}
		})
	}
}
