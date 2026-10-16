//go:build linux

package snellv6

import (
	"net"
	"syscall"

	"github.com/sagernet/sing/common"
	"golang.org/x/sys/unix"
)

// Pool.Take has exclusively claimed a ready session before this check. V6's
// session.Conn is its original dialer connection; the record reader consumes
// the complete protocol EOF, including shaped padding, before it becomes ready.
// Peek is nonblocking and neither consumes bytes nor changes deadlines. Keep
// queued bytes for the protocol reader: their presence alone is not a failure.
// A FIN arriving after this check remains a normal request failure, never replay.
func tunaIdleSocketUsable(conn net.Conn) bool {
	socket, ok := common.Cast[syscall.Conn](conn)
	if !ok {
		return true
	}
	raw, err := socket.SyscallConn()
	if err != nil {
		return false
	}
	usable := false
	err = raw.Control(func(fd uintptr) {
		var b [1]byte
		for {
			n, _, readErr := unix.Recvfrom(int(fd), b[:], unix.MSG_PEEK|unix.MSG_DONTWAIT)
			if readErr == unix.EINTR {
				continue
			}
			usable = n > 0 || readErr == unix.EAGAIN || readErr == unix.EWOULDBLOCK
			break
		}
	})
	return err == nil && usable
}
