//go:build !linux

package snellv4

import "net"

// The packaged Android core uses the Linux implementation. Host configuration
// validation remains portable; other platforms retain upstream behavior.
func tunaIdleSocketUsable(net.Conn) bool { return true }
