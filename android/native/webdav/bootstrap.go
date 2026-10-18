package tunnel

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Only storage lookups use this resolver. Invalid overrides fail closed.
func tunaStorageResolver(value string) *net.Resolver {
	return &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		if value == "system" {
			return tunaDialSystem(ctx, os.Getenv("TUNA_SYSTEM_DNS_SOCKET"), func(ctx context.Context, address string) (net.Conn, error) {
				return (&net.Dialer{}).DialContext(ctx, "unix", "@"+address)
			})
		}
		raw := value
		if !strings.Contains(raw, "://") {
			raw = "udp://" + raw
		}
		u, err := url.Parse(raw)
		if err != nil || (u.Scheme != "tcp" && u.Scheme != "udp") || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || net.ParseIP(u.Hostname()) == nil {
			return nil, fmt.Errorf("invalid storage bootstrap DNS")
		}
		port := u.Port()
		if port == "" {
			port = "53"
		}
		p, err := strconv.Atoi(port)
		if err != nil || p < 1 || p > 65535 {
			return nil, fmt.Errorf("invalid storage bootstrap port")
		}
		if u.Scheme == "tcp" {
			network = "tcp"
		}
		return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, net.JoinHostPort(u.Hostname(), port))
	}}
}

// Only net.Conn is exposed: net.Resolver must use TCP framing, not UnixConn's
// PacketConn methods. The Android bridge binds all questions to a physical network.
type tunaSystemStream struct {
	net.Conn
	cancel context.CancelFunc
	stop   func() bool
}

func (c *tunaSystemStream) Close() error { c.stop(); c.cancel(); return c.Conn.Close() }

func tunaDialSystem(ctx context.Context, socket string, dial func(context.Context, string) (net.Conn, error)) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if !strings.HasPrefix(socket, "tuna-dns-") || len(socket) <= len("tuna-dns-") || len(socket) > 100 || strings.ContainsAny(socket, "/\\\x00") {
		return nil, fmt.Errorf("Android system DNS bridge unavailable")
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	deadline, _ := ctx.Deadline()
	conn, err := dial(ctx, socket)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("Android system DNS bridge connection failed")
	}
	stream := &tunaSystemStream{Conn: conn, cancel: cancel, stop: context.AfterFunc(ctx, func() { conn.Close() })}
	if err = conn.SetDeadline(deadline); err != nil {
		stream.Close()
		return nil, err
	}
	timeout := time.Until(deadline).Milliseconds()
	if timeout < 1 {
		stream.Close()
		return nil, context.DeadlineExceeded
	}
	var header [2]byte
	binary.BigEndian.PutUint16(header[:], uint16(timeout))
	n, err := conn.Write(header[:])
	if err == nil && n != len(header) {
		err = io.ErrShortWrite
	}
	if err != nil {
		ctxErr := ctx.Err()
		stream.Close()
		if ctxErr != nil {
			return nil, ctxErr
		}
		return nil, err
	}
	return stream, nil
}
