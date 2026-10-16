//go:build !linux

package snellv6

import "net"

// Android uses the Linux socket check; other hosts preserve upstream behavior.
func tunaIdleSocketUsable(net.Conn) bool { return true }
