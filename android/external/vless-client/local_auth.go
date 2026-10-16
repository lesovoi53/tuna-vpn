// SPDX-License-Identifier: MIT
package main

import (
	"crypto/subtle"
	"io"
	"net"
)

type localAuth struct {
	Username    string
	Password    string
	AllowNoAuth bool
}

// RFC 1929. Never negotiate no-auth when credentials are required.
func authenticateSOCKS(conn net.Conn, auth localAuth) bool {
	h := make([]byte, 2)
	if _, err := io.ReadFull(conn, h); err != nil || h[0] != 5 || h[1] == 0 {
		return false
	}
	methods := make([]byte, int(h[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return false
	}
	method := byte(2)
	if auth.AllowNoAuth {
		method = 0
	}
	offered := false
	for _, m := range methods {
		if m == method {
			offered = true
		}
	}
	if !offered || (!auth.AllowNoAuth && (auth.Username == "" || auth.Password == "")) {
		conn.Write([]byte{5, 255})
		return false
	}
	if _, err := conn.Write([]byte{5, method}); err != nil {
		return false
	}
	if method == 0 {
		return true
	}
	if _, err := io.ReadFull(conn, h); err != nil || h[0] != 1 || h[1] == 0 {
		return false
	}
	user := make([]byte, int(h[1]))
	if _, err := io.ReadFull(conn, user); err != nil {
		return false
	}
	n := make([]byte, 1)
	if _, err := io.ReadFull(conn, n); err != nil || n[0] == 0 {
		return false
	}
	pass := make([]byte, int(n[0]))
	if _, err := io.ReadFull(conn, pass); err != nil {
		return false
	}
	ok := subtle.ConstantTimeCompare(user, []byte(auth.Username)) & subtle.ConstantTimeCompare(pass, []byte(auth.Password))
	status := byte(1)
	if ok == 1 {
		status = 0
	}
	if _, err := conn.Write([]byte{1, status}); err != nil {
		return false
	}
	return ok == 1
}
