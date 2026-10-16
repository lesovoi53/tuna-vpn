//go:build linux

package snellv4

import (
	"net"
	"syscall"

	"github.com/sagernet/sing/common"
	"golang.org/x/sys/unix"
)

// Only called after taking exclusive ownership of an idle session. A peek does
// not consume protocol bytes, change deadlines, wait, or replay application data.
// This detects an already delivered FIN/RST; it cannot predict a future failure.
func tunaIdleSocketUsable(conn net.Conn) bool {
	socket, ok := common.Cast[syscall.Conn](conn)
	if !ok {
		return true // Preserve behavior for dialers without a native socket.
	}
	raw, err := socket.SyscallConn()
	if err != nil {
		return false
	}
	usable := false
	err = raw.Control(func(fd uintptr) {
		var b [1]byte
		for {
			_, _, readErr := unix.Recvfrom(int(fd), b[:], unix.MSG_PEEK|unix.MSG_DONTWAIT)
			if readErr == unix.EINTR {
				continue
			}
			// An idle Snell stream has consumed its protocol EOF. Extra bytes or
			// a TCP EOF mean it must not be handed to a new request.
			usable = readErr == unix.EAGAIN || readErr == unix.EWOULDBLOCK
			break
		}
	})
	return err == nil && usable
}
