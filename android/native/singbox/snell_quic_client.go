// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from reF1nd/sing-box bccceeb3dabea6687212c1fff651a9bcb7733de1.
package snell
import (
 "context"
 "net"
 "os"
 "sync"
 "sync/atomic"
 "time"
 snellprotocol "github.com/sagernet/sing-snell"
 "github.com/sagernet/sing/common/buf"
 M "github.com/sagernet/sing/common/metadata"
 N "github.com/sagernet/sing/common/network"
 "github.com/sagernet/sing/common/pipe"
)
type quicDestCacheKey struct { source, destination M.Socksaddr }
type tunaQUICDestination struct { token uint64; expires time.Time }
func (h *Outbound) isRecentQUICDest(source, destination M.Socksaddr) bool {
 h.quicMu.Lock(); defer h.quicMu.Unlock()
 key := quicDestCacheKey{source,destination}; v,ok := h.quicDestCache[key]
 if ok && time.Now().Before(v.expires) { return true }; delete(h.quicDestCache,key); return false
}
func (h *Outbound) markQUICDest(source, destination M.Socksaddr) uint64 {
 h.quicMu.Lock(); defer h.quicMu.Unlock()
 if h.quicDestCache == nil { h.quicDestCache = make(map[quicDestCacheKey]tunaQUICDestination) }
 now:=time.Now()
 for k,v:= range h.quicDestCache { if now.After(v.expires) { delete(h.quicDestCache,k) } }
 if len(h.quicDestCache)>=512 { for k:=range h.quicDestCache { delete(h.quicDestCache,k); break } }
 h.quicDestSeq++; h.quicDestCache[quicDestCacheKey{source,destination}]=tunaQUICDestination{h.quicDestSeq,now.Add(5*time.Minute)}; return h.quicDestSeq
}
func (h *Outbound) refreshQUICDest(source, destination M.Socksaddr, token uint64) {
 h.quicMu.Lock(); defer h.quicMu.Unlock()
 key:=quicDestCacheKey{source,destination}; v,ok:=h.quicDestCache[key]
 if ok && v.token==token { v.expires=time.Now().Add(5*time.Minute);h.quicDestCache[key]=v }
}
type quicProxyLazyPacketConn struct {
	outbound    *Outbound
	ctx         context.Context
	cancel      context.CancelFunc
	source      M.Socksaddr
	destination M.Socksaddr
	sniffQUIC   bool

	once           sync.Once
	initDone       chan struct{}
	conn           net.PacketConn
	connErr        error
	initialSent    bool
	quicDestToken  uint64
	closeRequested atomic.Bool
	closed         chan struct{}
	closedOnce     sync.Once
	closeOnce      sync.Once
	deadlineAccess sync.Mutex
	initializing   net.Conn
	readDeadline   time.Time
	writeDeadline  time.Time
	readTimer      pipe.Deadline
	writeTimer     pipe.Deadline
}

func newQUICProxyLazyPacketConn(ctx context.Context, outbound *Outbound, source M.Socksaddr, destination M.Socksaddr, sniffQUIC bool) *quicProxyLazyPacketConn {
	initCtx := context.WithoutCancel(ctx)
	var cancel context.CancelFunc
	if deadline, loaded := ctx.Deadline(); loaded {
		initCtx, cancel = context.WithDeadline(initCtx, deadline)
	} else {
		initCtx, cancel = context.WithCancel(initCtx)
	}
	return &quicProxyLazyPacketConn{
		outbound: outbound, ctx: initCtx, cancel: cancel,
		source: source, destination: destination, sniffQUIC: sniffQUIC,
		initDone: make(chan struct{}), closed: make(chan struct{}),
		readTimer: pipe.MakeDeadline(), writeTimer: pipe.MakeDeadline(),
	}
}

func (c *quicProxyLazyPacketConn) initialize(payload []byte) {
	initCtx, initCancel := context.WithCancel(c.ctx)
	deadlineMonitorDone := make(chan struct{})
	var writeDeadlineExceeded atomic.Bool
	go func() {
		select {
		case <-c.writeTimer.Wait():
			writeDeadlineExceeded.Store(true)
			initCancel()
		case <-deadlineMonitorDone:
		}
	}()
	defer func() {
		close(deadlineMonitorDone)
		initCancel()
		c.cancel()
	}()
	useQUIC := c.sniffQUIC || snellprotocol.IsQUICInitial(payload)
	if useQUIC {
		c.conn, c.connErr = c.dialQUICProxy(initCtx, payload)
		c.initialSent = c.connErr == nil
		if c.connErr == nil && c.outbound != nil {
			c.quicDestToken = c.outbound.markQUICDest(c.source, c.destination)
		}
	} else {
		c.conn, c.connErr = c.dialUDPOverTCP(initCtx)
	}
	if c.connErr != nil && writeDeadlineExceeded.Load() {
		c.connErr = os.ErrDeadlineExceeded
	}
	c.deadlineAccess.Lock()
	c.initializing = nil
	if c.conn != nil {
		if !c.readDeadline.IsZero() {
			_ = c.conn.SetReadDeadline(c.readDeadline)
		}
		if !c.writeDeadline.IsZero() {
			_ = c.conn.SetWriteDeadline(c.writeDeadline)
		}
	}
	close(c.initDone)
	c.deadlineAccess.Unlock()
	if c.closeRequested.Load() {
		c.closeOnce.Do(func() {
			if c.conn != nil {
				_ = c.conn.Close()
			}
		})
		c.refreshQUICDestOnClose()
	}
}

func (c *quicProxyLazyPacketConn) registerInitializingConn(conn net.Conn) error {
	c.deadlineAccess.Lock()
	defer c.deadlineAccess.Unlock()
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
	}
	c.initializing = conn
	if c.writeDeadline.IsZero() {
		return nil
	}
	return conn.SetWriteDeadline(c.writeDeadline)
}

func (c *quicProxyLazyPacketConn) dialQUICProxy(ctx context.Context, initialPayload []byte) (net.PacketConn, error) {
	conn, err := c.outbound.dialer.DialContext(ctx, N.NetworkUDP, c.outbound.serverAddr)
	if err != nil {
		return nil, err
	}
	if err = c.registerInitializingConn(conn); err != nil {
		conn.Close()
		return nil, err
	}
	packetConn, err := snellprotocol.NewQUICProxyPacketConn(conn, c.outbound.psk, c.outbound.userKey, c.destination, initialPayload)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return packetConn, nil
}

func (c *quicProxyLazyPacketConn) dialUDPOverTCP(ctx context.Context) (net.PacketConn, error) {
	conn, err := c.outbound.dialer.DialContext(ctx, N.NetworkTCP, c.outbound.serverAddr)
	if err != nil {
		return nil, err
	}
	if err = c.registerInitializingConn(conn); err != nil {
		conn.Close()
		return nil, err
	}
	var packetConn net.PacketConn
	packetConn, err = c.outbound.client.DialPacketConn(conn)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return packetConn, nil
}

func (c *quicProxyLazyPacketConn) writeError() error {
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
	}
	select {
	case <-c.writeTimer.Wait():
		return os.ErrDeadlineExceeded
	default:
		return nil
	}
}

func (c *quicProxyLazyPacketConn) WriteTo(payload []byte, destination net.Addr) (int, error) {
	if err := c.writeError(); err != nil {
		return 0, err
	}
	if len(payload) == 0 {
		select {
		case <-c.initDone:
			if c.connErr != nil {
				return 0, c.connErr
			}
			return c.conn.WriteTo(payload, destination)
		default:
			return 0, nil
		}
	}
	initialized := false
	c.once.Do(func() {
		initialized = true
		c.initialize(payload)
	})
	<-c.initDone
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	default:
	}
	if c.connErr != nil {
		return 0, c.connErr
	}
	if initialized && c.initialSent {
		return len(payload), nil
	}
	return c.conn.WriteTo(payload, destination)
}

func (c *quicProxyLazyPacketConn) ReadFrom(payload []byte) (int, net.Addr, error) {
	conn, err := c.readConn()
	if err != nil {
		return 0, nil, err
	}
	return conn.ReadFrom(payload)
}

func (c *quicProxyLazyPacketConn) readConn() (net.PacketConn, error) {
	select {
	case <-c.closed:
		return nil, net.ErrClosed
	default:
	}
	select {
	case <-c.initDone:
	case <-c.closed:
		return nil, net.ErrClosed
	case <-c.readTimer.Wait():
		return nil, os.ErrDeadlineExceeded
	}
	select {
	case <-c.closed:
		return nil, net.ErrClosed
	default:
	}
	if c.connErr != nil {
		return nil, c.connErr
	}
	return c.conn, nil
}

func (c *quicProxyLazyPacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	conn, err := c.readConn()
	if err != nil {
		return M.Socksaddr{}, err
	}
	if packetReader, loaded := conn.(N.PacketReader); loaded {
		return packetReader.ReadPacket(buffer)
	}
	n, source, err := conn.ReadFrom(buffer.FreeBytes())
	if err != nil {
		return M.Socksaddr{}, err
	}
	buffer.Truncate(n)
	return M.SocksaddrFromNet(source).Unwrap(), nil
}

func (c *quicProxyLazyPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	defer buffer.Release()
	_, err := c.WriteTo(buffer.Bytes(), destination)
	return err
}

func (c *quicProxyLazyPacketConn) Close() error {
	c.closeRequested.Store(true)
	c.closedOnce.Do(func() {
		close(c.closed)
	})
	c.cancel()
	c.deadlineAccess.Lock()
	initializing := c.initializing
	c.deadlineAccess.Unlock()
	if initializing != nil {
		_ = initializing.Close()
	}
	select {
	case <-c.initDone:
		var closeErr error
		c.closeOnce.Do(func() {
			if c.conn != nil {
				closeErr = c.conn.Close()
			}
		})
		c.refreshQUICDestOnClose()
		return closeErr
	default:
		return nil
	}
}

func (c *quicProxyLazyPacketConn) refreshQUICDestOnClose() {
	if c.outbound != nil {
		c.outbound.refreshQUICDest(c.source, c.destination, c.quicDestToken)
	}
}

func (c *quicProxyLazyPacketConn) LocalAddr() net.Addr {
	select {
	case <-c.initDone:
		if c.conn != nil {
			return c.conn.LocalAddr()
		}
	default:
	}
	return &net.UDPAddr{}
}

func (c *quicProxyLazyPacketConn) SetDeadline(deadline time.Time) error {
	c.deadlineAccess.Lock()
	select {
	case <-c.initDone:
		conn := c.conn
		c.deadlineAccess.Unlock()
		if conn != nil {
			return conn.SetDeadline(deadline)
		}
		return nil
	default:
	}
	c.readDeadline = deadline
	c.writeDeadline = deadline
	c.readTimer.Set(deadline)
	c.writeTimer.Set(deadline)
	initializing := c.initializing
	c.deadlineAccess.Unlock()
	if initializing != nil {
		return initializing.SetWriteDeadline(deadline)
	}
	return nil
}

func (c *quicProxyLazyPacketConn) SetReadDeadline(deadline time.Time) error {
	c.deadlineAccess.Lock()
	select {
	case <-c.initDone:
		conn := c.conn
		c.deadlineAccess.Unlock()
		if conn != nil {
			return conn.SetReadDeadline(deadline)
		}
		return nil
	default:
	}
	c.readDeadline = deadline
	c.readTimer.Set(deadline)
	c.deadlineAccess.Unlock()
	return nil
}

func (c *quicProxyLazyPacketConn) SetWriteDeadline(deadline time.Time) error {
	c.deadlineAccess.Lock()
	select {
	case <-c.initDone:
		conn := c.conn
		c.deadlineAccess.Unlock()
		if conn != nil {
			return conn.SetWriteDeadline(deadline)
		}
		return nil
	default:
	}
	c.writeDeadline = deadline
	c.writeTimer.Set(deadline)
	initializing := c.initializing
	c.deadlineAccess.Unlock()
	if initializing != nil {
		return initializing.SetWriteDeadline(deadline)
	}
	return nil
}
