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

	snell "github.com/sagernet/sing-snell"
	"github.com/sagernet/sing-snell/snellv4"
	"github.com/sagernet/sing-snell/snellv5"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// Local transport only. No production endpoint or account is used.
type echoHandler struct{}

func (echoHandler) NewConnectionEx(ctx context.Context, conn net.Conn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		_, err := io.Copy(conn, conn)
		conn.Close()
		if onClose != nil {
			onClose(err)
		}
	}()
}
func (echoHandler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	conn.Close()
	if onClose != nil {
		onClose(nil)
	}
}

func localClient(t *testing.T, reuse bool, obfs snell.ObfsMode) *snellv4.Client {
	client, _, _ := localClientControl(t, reuse, obfs)
	return client
}

func localClientControl(t *testing.T, reuse bool, obfs snell.ObfsMode) (*snellv4.Client, func(bool), func() int) {
	t.Helper()
	psk := []byte("local-test-key-with-no-production-value")
	service, err := snellv5.NewService(snellv5.ServiceOptions{PSK: psk, ObfsMode: obfs, Handler: echoHandler{}})
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	connections := []net.Conn{}
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
	client, err := snellv4.NewClient(snellv4.ClientOptions{PSK: psk, Reuse: reuse, ObfsMode: obfs, ObfsHost: "test.invalid", Dialer: N.SystemDialer, Server: M.ParseSocksaddr(listener.Addr().String())})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		client.Close()
		listener.Close()
		mu.Lock()
		defer mu.Unlock()
		for _, c := range connections {
			c.Close()
		}
	})
	return client, func(reset bool) {
		mu.Lock()
		defer mu.Unlock()
		for _, c := range connections {
			if reset {
				c.(*net.TCPConn).SetLinger(0)
			}
			c.Close()
		}
	}, func() int { mu.Lock(); defer mu.Unlock(); return len(connections) }
}

func exchange(client *snellv4.Client, size int, value byte, earlyClose bool) error {
	conn, err := client.DialContext(context.Background(), M.ParseSocksaddr("echo.invalid:443"))
	if err != nil {
		return err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	sent := bytes.Repeat([]byte{value}, size)
	done := make(chan error, 1)
	go func() {
		_, err := conn.Write(sent)
		if err == nil && !earlyClose {
			if cw, ok := conn.(interface{ CloseWrite() error }); ok {
				err = cw.CloseWrite()
			}
		}
		done <- err
	}()
	want := size
	if earlyClose {
		want = min(size, 31)
	}
	received := make([]byte, want)
	if _, err = io.ReadFull(conn, received); err != nil {
		return fmt.Errorf("read %d: %w", size, err)
	}
	if !bytes.Equal(sent[:want], received) {
		return fmt.Errorf("corrupted %d-byte response", size)
	}
	if earlyClose {
		conn.Close()
	}
	if err = <-done; err != nil && !earlyClose {
		return fmt.Errorf("write %d: %w", size, err)
	}
	if !earlyClose {
		var tail [1]byte
		n, err := conn.Read(tail[:])
		if n != 0 || err != io.EOF {
			return fmt.Errorf("missing frame EOF: n=%d err=%v", n, err)
		}
	}
	return nil
}

func TestSnellLoopbackPayloadAndReuse(t *testing.T) {
	for _, reuse := range []bool{false, true} {
		for _, obfs := range []snell.ObfsMode{snell.ObfsModeNone, snell.ObfsModeHTTP} {
			t.Run(fmt.Sprintf("reuse=%v/obfs=%d", reuse, obfs), func(t *testing.T) {
				client, _, count := localClientControl(t, reuse, obfs)
				for round := 0; round < 3; round++ {
					for _, size := range []int{1, 64, 1400, 16384, 32767, 65536, 524288} {
						if err := exchange(client, size, byte(round+11), false); err != nil {
							t.Fatal(err)
						}
					}
				}
				if reuse && count() != 1 {
					t.Fatalf("healthy socket was not reused: %d connections", count())
				}
			})
		}
	}
}

func TestSnellConcurrentAbortDoesNotPoisonNextConnections(t *testing.T) {
	client := localClient(t, true, snell.ObfsModeHTTP)
	var wg sync.WaitGroup
	errors := make(chan error, 128)
	for worker := 0; worker < 8; worker++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			for i := 0; i < 16; i++ {
				if err := exchange(client, 65536, byte(worker*16+i), i%3 == 0); err != nil {
					errors <- err
				}
			}
		}(worker)
	}
	wg.Wait()
	close(errors)
	for err := range errors {
		t.Error(err)
	}
	for i := 0; i < 8; i++ {
		if err := exchange(client, 65536, byte(i), false); err != nil {
			t.Error(err)
		}
	}
}

func TestSnellRemoteCloseOfIdleReusableSocket(t *testing.T) {
	if runtime.GOOS != "linux" && runtime.GOOS != "android" {
		t.Skip("Android/Linux native socket check")
	}
	for _, reuse := range []bool{false, true} {
		for _, obfs := range []snell.ObfsMode{snell.ObfsModeNone, snell.ObfsModeHTTP} {
			for _, reset := range []bool{false, true} {
				t.Run(fmt.Sprintf("reuse=%v/obfs=%d/reset=%v", reuse, obfs, reset), func(t *testing.T) {
					client, drop, count := localClientControl(t, reuse, obfs)
					if err := exchange(client, 1400, 11, false); err != nil {
						t.Fatal(err)
					}
					// The remote endpoint stays available but closes its idle TCP socket.
					drop(reset)
					time.Sleep(50 * time.Millisecond)
					if err := exchange(client, 1400, 12, false); err != nil {
						t.Fatalf("new stream selected a remotely closed idle socket: %v", err)
					}
					if count() != 2 {
						t.Fatalf("expected one replacement connection, got %d", count())
					}
				})
			}
		}
	}
}
