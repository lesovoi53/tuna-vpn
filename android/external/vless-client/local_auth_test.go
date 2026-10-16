package main

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestSOCKSAuthentication(t *testing.T) {
	for _, tc := range []struct {
		name     string
		method   byte
		password string
		allow    bool
		expected bool
	}{
		{"no credentials", 0, "", false, false}, {"wrong password", 2, "wrong", false, false},
		{"valid credentials", 2, "local-pass", false, true}, {"explicit opt out", 0, "", true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server, client := net.Pipe()
			defer client.Close()
			client.SetDeadline(time.Now().Add(time.Second))
			result := make(chan bool, 1)
			go func() {
				defer server.Close()
				result <- authenticateSOCKS(server, localAuth{"local-user", "local-pass", tc.allow})
			}()
			client.Write([]byte{5, 1, tc.method})
			reply := make([]byte, 2)
			if _, err := io.ReadFull(client, reply); err != nil {
				t.Fatal(err)
			}
			if tc.method == 2 && reply[1] == 2 {
				client.Write(append(append([]byte{1, 10}, []byte("local-user")...), append([]byte{byte(len(tc.password))}, []byte(tc.password)...)...))
				if _, err := io.ReadFull(client, reply); err != nil {
					t.Fatal(err)
				}
			}
			if got := <-result; got != tc.expected {
				t.Fatalf("accepted=%v", got)
			}
		})
	}
}
