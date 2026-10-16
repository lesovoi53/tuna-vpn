// SPDX-License-Identifier: GPL-3.0-or-later
package socks5

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestTunaAuthenticationCannotDowngrade(t *testing.T) {
	for _, tc := range []struct {
		name     string
		methods  []byte
		password string
		expected bool
	}{
		{"no auth", []byte{0}, "", false}, {"both methods wrong password", []byte{0, 2}, "wrong", false},
		{"both methods valid password", []byte{0, 2}, "local-pass", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server, client := net.Pipe()
			defer client.Close()
			client.SetDeadline(time.Now().Add(time.Second))
			s := &Server{config: &Config{HandshakeTimeout: time.Second, AuthOpts: Auth{IngressCredentials: []Credential{{User: "local-user", Password: "local-pass"}}}}}
			result := make(chan error, 1)
			go func() { defer server.Close(); result <- s.handleAuthentication(server) }()
			client.Write(append([]byte{5, byte(len(tc.methods))}, tc.methods...))
			reply := make([]byte, 2)
			if _, err := io.ReadFull(client, reply); err != nil {
				t.Fatal(err)
			}
			if reply[1] == 0 {
				t.Fatal("authentication downgrade")
			}
			if reply[1] == 2 {
				client.Write(append(append([]byte{1, 10}, []byte("local-user")...), append([]byte{byte(len(tc.password))}, []byte(tc.password)...)...))
				if _, err := io.ReadFull(client, reply); err != nil {
					t.Fatal(err)
				}
			}
			if err := <-result; (err == nil) != tc.expected {
				t.Fatalf("unexpected authentication result: %v", err)
			}
		})
	}
}
