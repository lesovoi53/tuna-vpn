package local

// Android's system resolver cannot be discovered from resolv.conf or libc in
// this standalone native process. Only the application-owned bootstrap local
// transport is redirected to its authenticated NOT_VPN network bridge.
import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
)

func tunaSystemBootstrapEnabled(tag string) bool {
	return os.Getenv("TUNA_BOOTSTRAP_DNS") == "system" && (tag == "bootstrap" || strings.HasSuffix(tag, "-bootstrap"))
}

type tunaSystemBootstrap struct {
	dns.TransportAdapter
	ctx    context.Context
	cancel context.CancelFunc
	socket string
	dial   func(context.Context, string) (net.Conn, error)
}

var _ adapter.DNSTransport = (*tunaSystemBootstrap)(nil)

func newTunaSystemBootstrap(ctx context.Context, tag string) (adapter.DNSTransport, error) {
	socket := os.Getenv("TUNA_SYSTEM_DNS_SOCKET")
	if !strings.HasPrefix(socket, "tuna-dns-") || len(socket) <= len("tuna-dns-") || len(socket) > 100 || strings.ContainsAny(socket, "/\\\x00") {
		return nil, fmt.Errorf("Android system DNS bridge unavailable")
	}
	ctx, cancel := context.WithCancel(ctx)
	return &tunaSystemBootstrap{
		TransportAdapter: dns.NewTransportAdapter(C.DNSTypeLocal, tag, nil), ctx: ctx, cancel: cancel, socket: socket,
		dial: func(ctx context.Context, socket string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "unix", "@"+socket)
		},
	}, nil
}

func (t *tunaSystemBootstrap) Start(adapter.StartStage) error { return nil }
func (t *tunaSystemBootstrap) Close() error                   { t.cancel(); return nil }
func (t *tunaSystemBootstrap) Reset()                         {}

func (t *tunaSystemBootstrap) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	stop := context.AfterFunc(t.ctx, cancel)
	defer stop()
	if t.ctx.Err() != nil {
		return nil, t.ctx.Err()
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if message == nil || message.Response || len(message.Question) != 1 {
		return nil, fmt.Errorf("invalid system DNS question")
	}
	query, err := message.Pack()
	if err != nil {
		return nil, err
	}
	if len(query) < 12 || len(query) > 65535 {
		return nil, fmt.Errorf("invalid system DNS query size")
	}
	conn, err := t.dial(ctx, t.socket)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("Android system DNS bridge connection failed")
	}
	defer conn.Close()
	stopConn := context.AfterFunc(ctx, func() { conn.Close() })
	defer stopConn()
	deadline, _ := ctx.Deadline()
	if err = conn.SetDeadline(deadline); err != nil {
		return nil, err
	}
	remaining := time.Until(deadline).Milliseconds()
	if remaining < 1 {
		return nil, context.DeadlineExceeded
	}
	// Wire contract: uint16 timeout-ms, uint16 query-size, DNS wire query.
	packet := make([]byte, 4+len(query))
	binary.BigEndian.PutUint16(packet[:2], uint16(remaining))
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(query)))
	copy(packet[4:], query)
	n, err := conn.Write(packet)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	if n != len(packet) {
		return nil, io.ErrShortWrite
	}
	var size [2]byte
	if _, err = io.ReadFull(conn, size[:]); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	length := binary.BigEndian.Uint16(size[:])
	if length < 12 {
		return nil, fmt.Errorf("system DNS failed")
	}
	data := make([]byte, length)
	if _, err = io.ReadFull(conn, data); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	var response mDNS.Msg
	if err = response.Unpack(data); err != nil {
		return nil, err
	}
	if !response.Response || response.Truncated || response.Id != message.Id || len(response.Question) != 1 || response.Question[0] != message.Question[0] {
		return nil, fmt.Errorf("invalid system DNS response")
	}
	// NXDOMAIN and valid NODATA stay DNS answers, never transport failures.
	return &response, nil
}

func (t *tunaSystemBootstrap) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	go func() { callback(t.Exchange(ctx, message)) }()
}
