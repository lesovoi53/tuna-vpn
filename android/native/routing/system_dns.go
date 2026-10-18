package dns

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	stdnet "net"
	"os"
	"strings"
	"time"

	"github.com/xtls/xray-core/common/net"
	fdns "github.com/xtls/xray-core/features/dns"
	"golang.org/x/net/dns/dnsmessage"
)

// Hide UnixConn's PacketConn methods: net.Resolver must use TCP DNS framing.
type tunaSystemStream struct {
	stdnet.Conn
	stop   func() bool
	cancel context.CancelFunc
}

func (c *tunaSystemStream) Close() error {
	c.stop()
	c.cancel()
	return c.Conn.Close()
}

// DialTunaSystemDNS never falls back to libc, loopback, or a public DNS service.
// Android authenticates peer UID and binds each query to a NOT_VPN Network.
func DialTunaSystemDNS(ctx context.Context) (stdnet.Conn, error) {
	return dialTunaSystemDNS(ctx, os.Getenv("TUNA_SYSTEM_DNS_SOCKET"), func(ctx context.Context, address string) (stdnet.Conn, error) {
		return (&stdnet.Dialer{}).DialContext(ctx, "unix", "@"+address)
	})
}
func dialTunaSystemDNS(ctx context.Context, socket string, dial func(context.Context, string) (stdnet.Conn, error)) (stdnet.Conn, error) {
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
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return nil, err
		}
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
	var size [2]byte
	binary.BigEndian.PutUint16(size[:], uint16(timeout))
	if err = tunaSystemWrite(conn, size[:]); err != nil {
		contextErr := ctx.Err()
		stream.Close()
		if contextErr != nil {
			return nil, contextErr
		}
		return nil, err
	}
	return stream, nil
}

type tunaSystemDNSServer struct {
	// Production always uses DialTunaSystemDNS; the seam allows wire tests
	// without a live Android abstract socket or any external DNS request.
	dial func(context.Context) (stdnet.Conn, error)
}

func (*tunaSystemDNSServer) Name() string         { return "TUNA-Android-System-DIRECT" }
func (*tunaSystemDNSServer) IsDisableCache() bool { return true }
func (s *tunaSystemDNSServer) QueryIP(ctx context.Context, domain string, option fdns.IPOption) ([]net.IP, uint32, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, 0, err
	}
	name, err := dnsmessage.NewName(strings.TrimSuffix(domain, ".") + ".")
	if err != nil {
		return nil, 0, err
	}
	types := []dnsmessage.Type{}
	if option.IPv4Enable {
		types = append(types, dnsmessage.TypeA)
	}
	if option.IPv6Enable {
		types = append(types, dnsmessage.TypeAAAA)
	}
	var addresses []net.IP
	ttl := uint32(60)
	for index, kind := range types {
		query := dnsmessage.Message{Header: dnsmessage.Header{ID: 1, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: kind, Class: dnsmessage.ClassINET}}}
		data, e := query.Pack()
		if e != nil {
			return nil, 0, e
		}
		deadline, _ := ctx.Deadline()
		attempt, cancelAttempt := context.WithTimeout(ctx, time.Until(deadline)/time.Duration(len(types)-index))
		dial := s.dial
		if dial == nil {
			dial = DialTunaSystemDNS
		}
		conn, e := dial(attempt)
		if e != nil {
			cancelAttempt()
			return nil, 0, e
		}
		response, e := tunaSystemExchange(conn, data)
		conn.Close()
		cancelAttempt()
		if ctx.Err() != nil {
			return nil, 0, ctx.Err()
		}
		if e != nil {
			return nil, 0, e
		}
		var message dnsmessage.Message
		if e = message.Unpack(response); e != nil {
			return nil, 0, e
		}
		if !message.Response || message.Truncated || message.ID != query.ID || len(message.Questions) != 1 || message.Questions[0] != query.Questions[0] {
			return nil, 0, fmt.Errorf("invalid system DNS response")
		}
		if message.RCode != dnsmessage.RCodeSuccess {
			return nil, 0, fdns.RCodeError(message.RCode)
		}
		for _, answer := range message.Answers {
			switch body := answer.Body.(type) {
			case *dnsmessage.AResource:
				if kind == dnsmessage.TypeA {
					addresses = append(addresses, net.IP(body.A[:]))
				}
			case *dnsmessage.AAAAResource:
				if kind == dnsmessage.TypeAAAA {
					addresses = append(addresses, net.IP(body.AAAA[:]))
				}
			}
			if answer.Header.TTL < ttl {
				ttl = answer.Header.TTL
			}
		}
	}
	if len(addresses) == 0 {
		return nil, ttl, fdns.ErrEmptyResponse
	}
	return addresses, ttl, nil
}
func tunaSystemExchange(conn stdnet.Conn, query []byte) ([]byte, error) {
	if len(query) < 12 || len(query) > 65535 {
		return nil, fmt.Errorf("invalid system DNS query size")
	}
	var size [2]byte
	binary.BigEndian.PutUint16(size[:], uint16(len(query)))
	if err := tunaSystemWrite(conn, append(size[:], query...)); err != nil {
		return nil, err
	}
	if _, err := io.ReadFull(conn, size[:]); err != nil {
		return nil, err
	}
	length := binary.BigEndian.Uint16(size[:])
	if length < 12 {
		return nil, fmt.Errorf("system DNS failed")
	}
	data := make([]byte, length)
	_, err := io.ReadFull(conn, data)
	return data, err
}

func tunaSystemWrite(conn stdnet.Conn, data []byte) error {
	n, err := conn.Write(data)
	if err == nil && n != len(data) {
		err = io.ErrShortWrite
	}
	return err
}
