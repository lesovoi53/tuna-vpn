package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/json"
	"io"
	"math/big"
	"net"
	"testing"
	"time"

	"github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/xray-core/transport/internet/finalmask/fragment"
)

// Observe the actual pinned native wrapper's writes, not a reimplementation of splitting.
type fragmentCapture struct{ writes [][]byte }

func (c *fragmentCapture) Write(p []byte) (int, error) {
	c.writes = append(c.writes, append([]byte(nil), p...))
	return len(p), nil
}

func TestTunaFragmentModesCompleteRealTLSHandshake(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "vpn.invalid"},
		DNSNames: []string{"vpn.invalid"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(leaf)
	certificate := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: leaf}
	for _, version := range []uint16{tls.VersionTLS12, tls.VersionTLS13} {
		for _, tc := range []struct{ name, settings string }{
			{"tls", `{"packets":"tlshello","length":"3-3","delay":"0"}`},
			{"tcp", `{"packets":"1","length":"3-3","delay":"0"}`},
			{"both", `{"packets":"tlshello","length":"3-3","delays":["0","0"]}`},
		} {
			t.Run(tls.VersionName(version)+"/"+tc.name, func(t *testing.T) {
				local, remote := net.Pipe()
				defer local.Close()
				defer remote.Close()
				deadline := time.Now().Add(5 * time.Second)
				local.SetDeadline(deadline)
				remote.SetDeadline(deadline)
				var input conf.FragmentMask
				if err := json.Unmarshal([]byte(tc.settings), &input); err != nil {
					t.Fatal(err)
				}
				built, err := input.Build()
				if err != nil {
					t.Fatal(err)
				}
				wrapped, err := fragment.NewConnClient(built.(*fragment.Config), local, false)
				if err != nil {
					t.Fatal(err)
				}
				client := tls.Client(wrapped, &tls.Config{RootCAs: pool, ServerName: "vpn.invalid", MinVersion: version, MaxVersion: version})
				server := tls.Server(remote, &tls.Config{Certificates: []tls.Certificate{certificate}, MinVersion: version, MaxVersion: version})
				done := make(chan error, 1)
				go func() {
					if err := server.Handshake(); err != nil {
						done <- err
						return
					}
					message := make([]byte, 4)
					if _, err := io.ReadFull(server, message); err != nil {
						done <- err
						return
					}
					_, err := server.Write(message)
					done <- err
				}()
				ctx, cancel := context.WithDeadline(context.Background(), deadline)
				defer cancel()
				if err := client.HandshakeContext(ctx); err != nil {
					t.Fatal(err)
				}
				if _, err := client.Write([]byte("ping")); err != nil {
					t.Fatal(err)
				}
				echo := make([]byte, 4)
				if _, err := io.ReadFull(client, echo); err != nil || string(echo) != "ping" {
					t.Fatalf("TLS echo: %q %v", echo, err)
				}
				if err := <-done; err != nil {
					t.Fatal(err)
				}
			})
		}
	}
}
func (*fragmentCapture) Read([]byte) (int, error)         { return 0, net.ErrClosed }
func (*fragmentCapture) Close() error                     { return nil }
func (*fragmentCapture) LocalAddr() net.Addr              { return nil }
func (*fragmentCapture) RemoteAddr() net.Addr             { return nil }
func (*fragmentCapture) SetDeadline(time.Time) error      { return nil }
func (*fragmentCapture) SetReadDeadline(time.Time) error  { return nil }
func (*fragmentCapture) SetWriteDeadline(time.Time) error { return nil }

func TestTunaFragmentModesPreserveHandshakeAndSeparateRecordsFromWrites(t *testing.T) {
	payload := []byte{1, 0, 0, 8, 3, 3, 1, 2, 3, 4, 5, 6}
	hello := append([]byte{22, 3, 1, 0, byte(len(payload))}, payload...)
	for _, tc := range []struct {
		name, settings  string
		records, writes int
	}{
		{"tls", `{"packets":"tlshello","length":"3-3","delay":"0"}`, 4, 1},
		{"tcp", `{"packets":"1","length":"3-3","delay":"0"}`, 1, 6},
		{"both", `{"packets":"tlshello","length":"3-3","delays":["0","0"]}`, 4, 4},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var input conf.FragmentMask
			if err := json.Unmarshal([]byte(tc.settings), &input); err != nil {
				t.Fatal(err)
			}
			built, err := input.Build()
			if err != nil {
				t.Fatal(err)
			}
			capture := &fragmentCapture{}
			conn, err := fragment.NewConnClient(built.(*fragment.Config), capture, false)
			if err != nil {
				t.Fatal(err)
			}
			if n, err := conn.Write(hello); err != nil || n != len(hello) {
				t.Fatalf("write %d %v", n, err)
			}
			if len(capture.writes) != tc.writes {
				t.Fatalf("native writes: got %d want %d", len(capture.writes), tc.writes)
			}
			wire := bytes.Join(capture.writes, nil)
			var restored []byte
			records := 0
			for len(wire) > 0 {
				if len(wire) < 5 || wire[0] != 22 {
					t.Fatal("invalid TLS record")
				}
				n := int(binary.BigEndian.Uint16(wire[3:5]))
				if len(wire) < 5+n {
					t.Fatal("truncated record")
				}
				restored = append(restored, wire[5:5+n]...)
				wire = wire[5+n:]
				records++
			}
			if records != tc.records || !bytes.Equal(payload, restored) {
				t.Fatal("handshake changed or wrong record count")
			}
			capture.writes = nil
			if _, err := conn.Write(hello); err != nil || len(capture.writes) != 1 || !bytes.Equal(hello, capture.writes[0]) {
				t.Fatal("later writes must remain unchanged")
			}
		})
	}
}
