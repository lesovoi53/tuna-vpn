package snelllab

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	snell "github.com/sagernet/sing-snell"
	"github.com/sagernet/sing-snell/snellv4"
	"github.com/sagernet/sing-snell/snellv5"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

type packetEcho struct{}

func (packetEcho) NewConnectionEx(ctx context.Context, c net.Conn, _, _ M.Socksaddr, close N.CloseHandlerFunc) {
	go func() {
		_, err := io.Copy(c, c)
		c.Close()
		if close != nil {
			close(err)
		}
	}()
}
func (packetEcho) NewPacketConnectionEx(ctx context.Context, c N.PacketConn, _, _ M.Socksaddr, close N.CloseHandlerFunc) {
	go func() {
		defer c.Close()
		pc := bufio.NewNetPacketConn(c)
		for {
			payload := buf.NewSize(65535)
			destination, err := c.ReadPacket(payload)
			if err != nil {
				payload.Release()
				if close != nil {
					close(err)
				}
				return
			}
			if destination.IsFqdn() {
				destination = M.ParseSocksaddr("192.0.2.9:53")
			}
			_, err = pc.WriteTo(payload.Bytes(), destination)
			payload.Release()
			if err != nil {
				fmt.Println("fixture UDP write:", err)
				if close != nil {
					close(err)
				}
				return
			}
		}
	}()
}

func TestSnellPacketRoundtripConcurrentAndSizes(t *testing.T) {
	for _, obfs := range []snell.ObfsMode{snell.ObfsModeNone, snell.ObfsModeHTTP, snell.ObfsModeTLS} {
		t.Run(fmt.Sprint(obfs), func(t *testing.T) {
			psk := []byte("local-only-packet-fixture-not-a-real-secret")
			service, err := snellv5.NewService(snellv5.ServiceOptions{PSK: psk, ObfsMode: obfs, Handler: packetEcho{}})
			if err != nil {
				t.Fatal(err)
			}
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			var peersMu sync.Mutex
			var peers []net.Conn
			defer func() {
				peersMu.Lock()
				defer peersMu.Unlock()
				for _, c := range peers {
					c.Close()
				}
			}()
			go func() {
				for {
					c, e := listener.Accept()
					if e != nil {
						return
					}
					peersMu.Lock()
					peers = append(peers, c)
					peersMu.Unlock()
					go func() {
						if e := service.NewConnection(context.Background(), c, M.Socksaddr{}, nil); e != nil {
							fmt.Println("fixture connection:", e)
							c.Close()
						}
					}()
				}
			}()
			client, err := snellv4.NewClient(snellv4.ClientOptions{PSK: psk, Reuse: true, ObfsMode: obfs, ObfsHost: "fixture.invalid", Dialer: N.SystemDialer, Server: M.ParseSocksaddr(listener.Addr().String())})
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			raw, err := net.Dial("tcp", listener.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			packet, err := client.DialPacketConn(raw)
			if err != nil {
				t.Fatal(err)
			}
			defer packet.Close()
			packet.SetDeadline(time.Now().Add(15 * time.Second))
			destination := M.ParseSocksaddr("dns.fixture.invalid:53")
			// The pinned implementation increases its per-record ceiling after each
			// record. Warm it up before checking larger datagrams; this is not a VPN fix.
			for i := 0; i < 16; i++ {
				if _, err = packet.WriteTo([]byte{byte(i)}, destination); err != nil {
					t.Fatal(err)
				}
				response := make([]byte, 65535)
				if _, _, err = packet.ReadFrom(response); err != nil {
					t.Fatal(err)
				}
			}
			for _, size := range []int{1, 512, 1400, 4096, 8192, 16300} {
				wanted := bytes.Repeat([]byte{byte(size)}, size)
				if _, err = packet.WriteTo(wanted, destination); err != nil {
					t.Fatalf("size %d write: %v", size, err)
				}
				response := make([]byte, 65535)
				n, addr, e := packet.ReadFrom(response)
				if e != nil {
					t.Fatalf("size %d read: %v", size, e)
				}
				if !bytes.Equal(wanted, response[:n]) || addr.String() != "192.0.2.9:53" {
					t.Fatalf("size %d reply mismatch: %d %v", size, n, addr)
				}
			}
			// Snell caps a datagram record (including address headers) at 0x3fff.
			// A rejected oversized datagram must not poison subsequent valid packets.
			if _, e := packet.WriteTo(make([]byte, 16384), destination); !errors.Is(e, snell.ErrPayloadTooLarge) {
				t.Fatalf("oversized packet result: %v", e)
			}
			const workers = 8
			const each = 16
			errors := make(chan error, workers)
			var wg sync.WaitGroup
			for worker := 0; worker < workers; worker++ {
				wg.Add(1)
				go func(w int) {
					defer wg.Done()
					for seq := 0; seq < each; seq++ {
						body := bytes.Repeat([]byte{byte(w)}, 1400)
						binary.BigEndian.PutUint32(body, uint32(w*each+seq))
						if _, e := packet.WriteTo(body, M.ParseSocksaddr("[2001:db8::5]:443")); e != nil {
							errors <- e
							return
						}
					}
				}(worker)
			}
			seen := map[uint32]bool{}
			for count := 0; count < workers*each; count++ {
				body := make([]byte, 65535)
				n, addr, e := packet.ReadFrom(body)
				if e != nil {
					t.Fatal(e)
				}
				if n != 1400 || addr.String() != "[2001:db8::5]:443" {
					t.Fatalf("parallel reply: %d %v", n, addr)
				}
				id := binary.BigEndian.Uint32(body)
				if id >= workers*each || seen[id] {
					t.Fatal("duplicate or invalid payload", id)
				}
				seen[id] = true
				if !bytes.Equal(body[4:n], bytes.Repeat([]byte{byte(id / each)}, n-4)) {
					t.Fatal("interleaved payload", id)
				}
			}
			wg.Wait()
			close(errors)
			for e := range errors {
				t.Error(e)
			}
		})
	}
}
