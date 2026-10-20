package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type poolFixture struct {
	listener net.Listener
	id       byte
	fail     atomic.Bool
	calls    atomic.Int32
	udp      atomic.Int32
	mu       sync.Mutex
	streams  map[net.Conn]bool
	workers  sync.WaitGroup
}

func newPoolFixture(t *testing.T, id byte) *poolFixture {
	return newPoolFixtureWithAuth(t, id, true)
}

func newPoolFixtureWithAuth(t *testing.T, id byte, authentication bool) *poolFixture {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	fixture := &poolFixture{listener: listener, id: id, streams: map[net.Conn]bool{}}
	fixture.workers.Add(1)
	go func() {
		defer fixture.workers.Done()
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			fixture.mu.Lock()
			fixture.streams[conn] = true
			fixture.mu.Unlock()
			fixture.workers.Add(1)
			go func() {
				defer fixture.workers.Done()
				defer conn.Close()
				defer func() {
					fixture.mu.Lock()
					delete(fixture.streams, conn)
					fixture.mu.Unlock()
				}()
				conn.SetDeadline(time.Now().Add(3 * time.Minute))
				if authentication {
					if !poolAuthenticate(conn, "child", "secret") {
						return
					}
				} else {
					greeting, err := poolRead(conn, 3)
					if err != nil || !bytes.Equal(greeting, []byte{5, 1, 0}) {
						return
					}
					conn.Write([]byte{5, 0})
				}
				header, err := poolRead(conn, 4)
				if err != nil {
					return
				}
				address, err := poolAddress(conn, header[3])
				if err != nil {
					return
				}
				fixture.calls.Add(1)
				if fixture.fail.Load() {
					poolReply(conn, 5)
					return
				}
				if header[1] == 3 {
					fixture.serveUDP(conn, header[3], address)
					return
				}
				if header[1] != 1 || header[3] != 3 || string(address[1:len(address)-2]) != "unresolved.example.invalid" {
					poolReply(conn, 8)
					return
				}
				poolReply(conn, 0)
				for {
					value, err := poolRead(conn, 1)
					if err != nil {
						return
					}
					if _, err = conn.Write([]byte{id, value[0]}); err != nil {
						return
					}
				}
			}()
		}
	}()
	t.Cleanup(func() {
		listener.Close()
		fixture.mu.Lock()
		for conn := range fixture.streams {
			conn.Close()
		}
		fixture.mu.Unlock()
		fixture.workers.Wait()
	})
	return fixture
}

func (fixture *poolFixture) serveUDP(control net.Conn, kind byte, address []byte) {
	declared, valid := poolUDPAddress(kind, address)
	if !valid || !declared.Addr().IsLoopback() || declared.Port() == 0 {
		poolReply(control, 1)
		return
	}
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return
	}
	defer udp.Close()
	port := udp.LocalAddr().(*net.UDPAddr).Port
	control.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	done := make(chan struct{})
	go func() {
		defer close(done)
		var buffer [1]byte
		control.Read(buffer[:])
		udp.Close()
	}()
	buffer := make([]byte, 4096)
	for {
		n, peer, err := udp.ReadFromUDP(buffer)
		if err != nil {
			break
		}
		if peer.AddrPort().Addr().Unmap() != declared.Addr() || peer.Port != int(declared.Port()) {
			continue
		}
		dns, ok := poolUDPPacket(buffer[:n])
		if !ok {
			continue
		}
		fixture.udp.Add(1)
		dns[2] |= 0x80
		udp.WriteToUDP(buffer[:n], peer)
	}
	control.Close()
	<-done
}

func startTestPool(t *testing.T, strategy string) (*masterPool, string, []*poolFixture, context.CancelFunc) {
	t.Helper()
	fixtures := []*poolFixture{newPoolFixture(t, 'A'), newPoolFixture(t, 'B')}
	cfg := poolConfig{Version: 1, Username: "front", Password: "private", Strategy: strategy}
	for i, fixture := range fixtures {
		cfg.Members = append(cfg.Members, poolMemberConfig{ID: fmt.Sprintf("member-%d", i),
			Listen: fixture.listener.Addr().String(), Username: "child", Password: "secret",
			ConfigPath: fmt.Sprintf("/proc/self/fd/%d", 10+i*2), ResolversPath: fmt.Sprintf("/proc/self/fd/%d", 11+i*2)})
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cfg.Listen = listener.Addr().String()
	pool := newMasterPool(cfg, io.Discard)
	for _, member := range pool.members {
		pool.markReady(member, true)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- pool.serve(ctx, listener) }()
	t.Cleanup(func() {
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Error(err)
			}
		case <-time.After(3 * time.Second):
			t.Error("pool shutdown did not finish")
		}
	})
	return pool, listener.Addr().String(), fixtures, cancel
}

func poolTestAuth(t *testing.T, address string) net.Conn {
	t.Helper()
	conn, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	conn.Write([]byte{5, 1, 2})
	reply, err := poolRead(conn, 2)
	if err != nil || !bytes.Equal(reply, []byte{5, 2}) {
		t.Fatalf("method: %v %v", reply, err)
	}
	conn.Write([]byte("\x01\x05front\x07private"))
	reply, err = poolRead(conn, 2)
	if err != nil || !bytes.Equal(reply, []byte{1, 0}) {
		t.Fatalf("auth: %v %v", reply, err)
	}
	return conn
}

func poolTestConnect(t *testing.T, address string) net.Conn {
	t.Helper()
	conn := poolTestAuth(t, address)
	host := "unresolved.example.invalid"
	request := append([]byte{5, 1, 0, 3, byte(len(host))}, []byte(host)...)
	conn.Write(append(request, 1, 187))
	reply, err := poolRead(conn, 10)
	if err != nil || reply[1] != 0 {
		t.Fatalf("CONNECT: %v %v", reply, err)
	}
	return conn
}

func fixtureExchange(t *testing.T, conn net.Conn, value byte) byte {
	t.Helper()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	conn.Write([]byte{value})
	reply, err := poolRead(conn, 2)
	if err != nil || reply[1] != value {
		t.Fatalf("stream bytes: %v %v", reply, err)
	}
	return reply[0]
}

func waitPoolActive(t *testing.T, pool *masterPool, count int) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		pool.mu.Lock()
		active := 0
		for _, member := range pool.members {
			active += member.active
		}
		pool.mu.Unlock()
		if active == count {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("active connections did not reach %d", count)
}

func TestPoolRoundRobinPinsStreamsAndCleanup(t *testing.T) {
	pool, address, _, cancel := startTestPool(t, "round_robin")
	first := poolTestConnect(t, address)
	second := poolTestConnect(t, address)
	third := poolTestConnect(t, address)
	if fixtureExchange(t, first, 11) != 'A' || fixtureExchange(t, second, 12) != 'B' || fixtureExchange(t, third, 13) != 'A' {
		t.Fatal("round robin order")
	}
	pool.markReady(pool.members[0], false)
	if fixtureExchange(t, first, 14) != 'A' {
		t.Fatal("active stream migrated")
	}
	fourth := poolTestConnect(t, address)
	if fixtureExchange(t, fourth, 15) != 'B' {
		t.Fatal("not-ready member selected")
	}
	cancel()
	for _, conn := range []net.Conn{first, second, third, fourth} {
		conn.SetReadDeadline(time.Now().Add(time.Second))
		if _, err := poolRead(conn, 1); err == nil {
			t.Fatal("stream survived shutdown")
		}
	}
	waitPoolActive(t, pool, 0)
}

func TestPoolLeastConnectionsCountsLiveStreams(t *testing.T) {
	pool, address, _, _ := startTestPool(t, "least_connections")
	first := poolTestConnect(t, address)
	second := poolTestConnect(t, address)
	third := poolTestConnect(t, address)
	if fixtureExchange(t, first, 1) != 'A' || fixtureExchange(t, second, 2) != 'B' || fixtureExchange(t, third, 3) != 'A' {
		t.Fatal("initial least connections allocation")
	}
	second.Close()
	waitPoolActive(t, pool, 2)
	fourth := poolTestConnect(t, address)
	if fixtureExchange(t, fourth, 4) != 'B' {
		t.Fatal("least loaded member not chosen")
	}
}

func TestPoolFailedRemoteConnectRetriesBeforePayload(t *testing.T) {
	_, address, fixtures, _ := startTestPool(t, "round_robin")
	fixtures[0].fail.Store(true)
	first := poolTestConnect(t, address)
	if fixtureExchange(t, first, 99) != 'B' || fixtures[0].calls.Load() != 1 || fixtures[1].calls.Load() != 1 {
		t.Fatal("failed remote CONNECT did not fail over")
	}
	second := poolTestConnect(t, address)
	if fixtureExchange(t, second, 88) != 'B' || fixtures[0].calls.Load() != 2 {
		t.Fatal("destination refusal incorrectly cooled healthy member")
	}
}

func TestPoolRejectsNoAuthWrongPasswordAndBind(t *testing.T) {
	_, address, fixtures, _ := startTestPool(t, "round_robin")
	conn, err := net.Dial("tcp", address)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(time.Second))
	conn.Write([]byte{5, 1, 0})
	reply, err := poolRead(conn, 2)
	if err != nil || !bytes.Equal(reply, []byte{5, 255}) {
		t.Fatal("no-auth accepted")
	}
	wrong, err := net.Dial("tcp", address)
	if err != nil {
		t.Fatal(err)
	}
	defer wrong.Close()
	wrong.SetDeadline(time.Now().Add(time.Second))
	wrong.Write([]byte{5, 1, 2})
	poolRead(wrong, 2)
	wrong.Write([]byte("\x01\x05front\x05wrong"))
	reply, err = poolRead(wrong, 2)
	if err != nil || !bytes.Equal(reply, []byte{1, 1}) {
		t.Fatal("wrong password accepted")
	}
	bind := poolTestAuth(t, address)
	bind.Write([]byte{5, 2, 0, 1})
	reply, err = poolRead(bind, 10)
	if err != nil || reply[1] != 7 {
		t.Fatal("BIND not explicitly rejected")
	}
	if fixtures[0].calls.Load()+fixtures[1].calls.Load() != 0 {
		t.Fatal("unauthenticated/unsupported connection reached a child")
	}
}

func TestPoolAllUnavailableNeverFallsBackDirect(t *testing.T) {
	pool, address, fixtures, _ := startTestPool(t, "round_robin")
	pool.markReady(pool.members[0], false)
	pool.markReady(pool.members[1], false)
	conn := poolTestAuth(t, address)
	conn.Write([]byte{5, 1, 0, 1, 192, 0, 2, 1, 0, 80})
	reply, err := poolRead(conn, 10)
	if err != nil || reply[1] == 0 {
		t.Fatal("unavailable pool succeeded")
	}
	if fixtures[0].calls.Load()+fixtures[1].calls.Load() != 0 {
		t.Fatal("not-ready child dialed")
	}
}

func TestPoolDNSUDPAssociationPinnedFilteredAndClosed(t *testing.T) {
	pool, address, fixtures, _ := startTestPool(t, "round_robin")
	control := poolTestAuth(t, address)
	control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	reply, err := poolRead(control, 10)
	if err != nil || reply[1] != 0 || !bytes.Equal(reply[4:8], []byte{127, 0, 0, 1}) {
		t.Fatalf("UDP loopback association: %v %v", reply, err)
	}
	port := int(binary.BigEndian.Uint16(reply[8:10]))
	udp, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	packet := append([]byte{0, 0, 0, 1, 192, 0, 2, 53, 0, 53}, make([]byte, 12)...)
	packet[10], packet[11] = 1, 2
	packet[15] = 1
	packet = append(packet, []byte("\x07example\x07invalid\x00\x00\x01\x00\x01")...)
	udp.SetDeadline(time.Now().Add(time.Second))
	udp.Write(packet)
	buffer := make([]byte, 4096)
	if n, err := udp.Read(buffer); err != nil || n != len(packet) || buffer[12]&0x80 == 0 {
		t.Fatalf("DNS UDP delivery: %d %v", n, err)
	}
	pool.markReady(pool.members[0], false)
	packet[11]++
	udp.Write(packet)
	if _, err := udp.Read(buffer); err != nil || fixtures[1].udp.Load() != 0 {
		t.Fatal("UDP association migrated")
	}
	invalid := append([]byte{}, packet...)
	invalid[2] = 1
	udp.Write(invalid)
	invalid[2], invalid[9] = 0, 123
	udp.Write(invalid)
	spoof, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		t.Fatal(err)
	}
	spoof.Write(packet)
	spoof.Close()
	time.Sleep(80 * time.Millisecond)
	if fixtures[0].udp.Load() != 2 {
		t.Fatal("fragment, non-DNS UDP or other source reached child")
	}
	control.Close()
	waitPoolActive(t, pool, 0)
	udp.SetReadDeadline(time.Now().Add(80 * time.Millisecond))
	udp.Write(packet)
	if _, err := udp.Read(buffer); err == nil {
		t.Fatal("UDP survived control close")
	}
}

func TestPoolValidationAndSafeAggregateMarkers(t *testing.T) {
	cfg := poolConfig{Version: 1, Listen: "127.0.0.1:10000", Username: "u", Password: "p", Strategy: "round_robin",
		Members: []poolMemberConfig{
			{ID: "one", ConfigPath: "/proc/self/fd/3", ResolversPath: "/proc/self/fd/4", Listen: "127.0.0.1:10001", Username: "u1", Password: "p1"},
			{ID: "two", ConfigPath: "/proc/self/fd/5", ResolversPath: "/proc/self/fd/6", Listen: "127.0.0.1:10002", Username: "u2", Password: "p2"},
		}}
	if err := validatePoolConfig(cfg); err != nil {
		t.Fatal(err)
	}
	for _, mutate := range []func(*poolConfig){
		func(c *poolConfig) { c.Listen = "0.0.0.0:10000" },
		func(c *poolConfig) { c.Password = "" },
		func(c *poolConfig) { c.Members[1].ID = c.Members[0].ID },
		func(c *poolConfig) { c.Members[1].Listen = c.Members[0].Listen },
		func(c *poolConfig) { c.Members[1].ConfigPath = c.Members[0].ConfigPath },
		func(c *poolConfig) { c.Strategy = "unknown" },
		func(c *poolConfig) { c.Members = c.Members[:1] },
	} {
		bad := cfg
		bad.Members = append([]poolMemberConfig{}, cfg.Members...)
		mutate(&bad)
		if validatePoolConfig(bad) == nil {
			t.Fatal("invalid pool accepted")
		}
	}
	var log bytes.Buffer
	pool := newMasterPool(cfg, &log)
	pool.markReady(pool.members[0], true)
	pool.markReady(pool.members[1], true)
	pool.markReady(pool.members[0], false)
	if bytes.Count(log.Bytes(), []byte("TUNA_MASTER_READY\n")) != 1 ||
		bytes.Contains(log.Bytes(), []byte("TUNA_MASTER_CONNECTING")) ||
		bytes.Contains(log.Bytes(), []byte("p1")) || bytes.Contains(log.Bytes(), []byte("127.0.0.1")) {
		t.Fatalf("unsafe aggregate output: %s", log.String())
	}
	pool.markReady(pool.members[1], false)
	if bytes.Count(log.Bytes(), []byte("TUNA_MASTER_CONNECTING\n")) != 1 {
		t.Fatal("all unavailable not reported")
	}
}
