// Run with: go run tests/singbox_smoke.go <sing-box executable>
// Exercises the generated Android Snell profile against a loopback Snell server.
package main

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

func check(err error) {
	if err != nil {
		panic(err)
	}
}
func port() int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	check(err)
	p := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return p
}
func start(binary, dir, name string, cfg any, listen int) func() {
	raw, err := json.Marshal(cfg)
	check(err)
	path := filepath.Join(dir, name+".json")
	check(os.WriteFile(path, raw, 0600))
	cmd := exec.Command(binary, "run", "-c", path)
	cmd.Stderr = os.Stderr
	check(cmd.Start())
	stop := func() { cmd.Process.Kill(); cmd.Wait() }
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", listen), 100*time.Millisecond)
		if err == nil {
			conn.Close()
			return stop
		}
		time.Sleep(50 * time.Millisecond)
	}
	stop()
	panic("listener did not start")
}
func main() {
	if len(os.Args) != 2 && len(os.Args) != 3 {
		panic("expected sing-box executable path [generated fixture]")
	}
	binaryPath, err := filepath.Abs(os.Args[1])
	check(err)
	dir, err := os.MkdirTemp("", "tuna-smoke-")
	check(err)
	defer os.RemoveAll(dir)
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	check(err)
	defer echo.Close()
	go func() {
		for {
			c, e := echo.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	serverPort, clientPort := port(), port()
	fixture := "app/build/vpn-test-configs/snell.json"
	if len(os.Args) == 3 {
		fixture = os.Args[2]
	}
	raw, err := os.ReadFile(fixture)
	check(err)
	var client map[string]any
	check(json.Unmarshal(raw, &client))
	out := client["outbounds"].([]any)[0].(map[string]any)
	inbound := map[string]any{"type": "snell", "listen": "127.0.0.1", "listen_port": serverPort, "version": 5, "psk": "test+secret", "obfs_mode": "http"}
	if out["type"] == "vless" {
		inbound = map[string]any{"type": "vless", "listen": "127.0.0.1", "listen_port": serverPort, "users": []any{map[string]any{"uuid": out["uuid"]}}}
		if transport, ok := out["transport"]; ok {
			inbound["transport"] = transport
		}
		if tls, ok := out["tls"].(map[string]any); ok {
			key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
			check(err)
			cert := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), DNSNames: []string{"example.com"}, KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
			der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
			check(err)
			private, err := x509.MarshalECPrivateKey(key)
			check(err)
			certificate := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
			inbound["tls"] = map[string]any{"enabled": true, "certificate": []string{certificate}, "key": []string{string(pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: private}))}, "alpn": []string{"h2", "http/1.1"}}
			tls["certificate"] = []string{certificate}
			tls["alpn"] = []string{"h2", "http/1.1"}
		}
		if encryption, ok := out["encryption"].(string); ok && encryption != "none" {
			key, err := ecdh.X25519().GenerateKey(rand.Reader)
			check(err)
			out["encryption"] = "mlkem768x25519plus.native.1rtt." + base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes())
			inbound["decryption"] = "mlkem768x25519plus.native.600s." + base64.RawURLEncoding.EncodeToString(key.Bytes())
		}
	}
	server := map[string]any{
		"log":       map[string]any{"disabled": true},
		"inbounds":  []any{inbound},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
		"route":     map[string]any{"final": "direct"},
	}
	stopServer := start(binaryPath, dir, "server", server, serverPort)
	defer stopServer()
	out["server"] = "127.0.0.1"
	out["server_port"] = serverPort
	client["inbounds"].([]any)[0].(map[string]any)["listen_port"] = clientPort
	stopClient := start(binaryPath, dir, "client", client, clientPort)
	defer stopClient()
	for _, method := range []byte{0, 2} {
		c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", clientPort), time.Second)
		check(err)
		c.SetDeadline(time.Now().Add(time.Second))
		c.Write([]byte{5, 1, method})
		reply := make([]byte, 2)
		_, err = io.ReadFull(c, reply)
		if method == 0 {
			if err == nil && reply[1] == 0 {
				panic("anonymous SOCKS accepted")
			}
		} else {
			check(err)
			if reply[1] != 2 {
				panic("unexpected auth method")
			}
			c.Write(append(append([]byte{1, 10}, []byte("local-user")...), []byte{5, 'w', 'r', 'o', 'n', 'g'}...))
			_, err = io.ReadFull(c, reply)
			if err == nil && reply[1] == 0 {
				panic("wrong SOCKS password accepted")
			}
		}
		c.Close()
	}
	for n := 0; n < 3; n++ { // Repeated connections exercise reuse=true.
		c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", clientPort), time.Second)
		check(err)
		c.SetDeadline(time.Now().Add(5 * time.Second))
		_, err = c.Write([]byte{5, 1, 2})
		check(err)
		reply := make([]byte, 2)
		_, err = io.ReadFull(c, reply)
		check(err)
		if reply[0] != 5 || reply[1] != 2 {
			panic("SOCKS negotiation failed")
		}
		_, err = c.Write(append(append([]byte{1, 10}, []byte("local-user")...), append([]byte{10}, []byte("local-pass")...)...))
		check(err)
		_, err = io.ReadFull(c, reply)
		check(err)
		if reply[1] != 0 {
			panic("auth failed")
		}
		request := []byte{5, 1, 0, 1, 127, 0, 0, 1, 0, 0}
		binary2 := uint16(echo.Addr().(*net.TCPAddr).Port)
		binary.BigEndian.PutUint16(request[8:], binary2)
		_, err = c.Write(request)
		check(err)
		header := make([]byte, 4)
		_, err = io.ReadFull(c, header)
		check(err)
		if header[1] != 0 {
			panic("Snell connection rejected")
		}
		length := 6
		if header[3] == 4 {
			length = 18
		}
		if header[3] == 3 {
			b := make([]byte, 1)
			_, err = io.ReadFull(c, b)
			check(err)
			length = int(b[0]) + 2
		}
		_, err = io.ReadFull(c, make([]byte, length))
		check(err)
		payload := fmt.Sprintf("TUNA VPN native transport echo %d", n)
		_, err = io.WriteString(c, payload)
		check(err)
		received := make([]byte, len(payload))
		_, err = io.ReadFull(c, received)
		check(err)
		if string(received) != payload {
			panic("payload mismatch")
		}
		c.Close()
	}
	fmt.Println("PASS:", filepath.Base(fixture), "SOCKS rejects anonymous/wrong password; authenticated TCP echo, three connections")
}
