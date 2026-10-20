package main

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"masterdnsvpn-go/internal/config"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	poolMaxConnections = 256
	poolHandshakeLimit = 15 * time.Second
	poolConnectLimit   = 8 * time.Second
	poolCooldown       = 2 * time.Second
)

type poolDestinationError byte

func (code poolDestinationError) Error() string { return "child destination rejected" }

type poolMemberConfig struct {
	ID            string `json:"id"`
	ConfigPath    string `json:"configPath"`
	ResolversPath string `json:"resolversPath"`
	Listen        string `json:"listen"`
	Username      string `json:"username"`
	Password      string `json:"password"`
}

type poolConfig struct {
	Version  int                `json:"version"`
	Listen   string             `json:"listen"`
	Username string             `json:"username"`
	Password string             `json:"password"`
	Strategy string             `json:"strategy"`
	Members  []poolMemberConfig `json:"members"`
}

type poolMember struct {
	config  poolMemberConfig
	files   [2]*os.File
	ready   bool
	active  int
	cooling time.Time
}

type masterPool struct {
	config  poolConfig
	mu      sync.Mutex
	members []*poolMember
	next    int
	streams map[net.Conn]struct{}
	output  io.Writer
	last    string
}

func validPoolAuth(username, password string) bool {
	return len(username) > 0 && len(username) <= 255 && len(password) > 0 && len(password) <= 255
}

func poolEndpoint(raw string) (netip.AddrPort, error) {
	addr, err := netip.ParseAddrPort(raw)
	if err != nil || !addr.Addr().IsLoopback() || addr.Addr().Zone() != "" || addr.Port() == 0 {
		return netip.AddrPort{}, errors.New("pool endpoint rejected")
	}
	return netip.AddrPortFrom(addr.Addr().Unmap(), addr.Port()), nil
}

func validatePoolConfig(cfg poolConfig) error {
	if cfg.Version != 1 || len(cfg.Members) < 2 || len(cfg.Members) > 8 ||
		!validPoolAuth(cfg.Username, cfg.Password) ||
		(cfg.Strategy != "round_robin" && cfg.Strategy != "least_connections") {
		return errors.New("pool configuration rejected")
	}
	listen, err := poolEndpoint(cfg.Listen)
	if err != nil {
		return err
	}
	// Port uniqueness also prevents aliases (127.0.0.1 versus ::ffff:127.0.0.1)
	// from accidentally targeting another member's listener.
	ports := map[uint16]bool{listen.Port(): true}
	ids := map[string]bool{}
	paths := map[string]bool{}
	for _, member := range cfg.Members {
		endpoint, err := poolEndpoint(member.Listen)
		if err != nil || ports[endpoint.Port()] || !validPoolAuth(member.Username, member.Password) ||
			len(member.ID) == 0 || len(member.ID) > 64 || ids[member.ID] {
			return errors.New("pool member rejected")
		}
		for _, c := range member.ID {
			if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
				return errors.New("pool member id rejected")
			}
		}
		for _, path := range []string{member.ConfigPath, member.ResolversPath} {
			if !poolPrivatePath(path) || paths[path] {
				return errors.New("pool descriptor rejected")
			}
			paths[path] = true
		}
		ids[member.ID], ports[endpoint.Port()] = true, true
	}
	return nil
}

func loadPool(path string) (*masterPool, error) {
	// Android's memory launcher intentionally inherits descriptors. Mark ALL of
	// them close-on-exec before opening config or starting a child, including
	// the pool JSON and any descriptors belonging to other native transports.
	if err := poolSealInheritedFiles(); err != nil {
		return nil, err
	}
	if !poolPrivatePath(path) {
		return nil, errors.New("pool memory configuration required")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, errors.New("pool configuration unavailable")
	}
	defer file.Close()
	content, err := io.ReadAll(io.LimitReader(file, 65537))
	if err != nil || len(content) > 65536 {
		return nil, errors.New("pool configuration exceeds limit")
	}
	var cfg poolConfig
	decoder := json.NewDecoder(bytes.NewReader(content))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&cfg) != nil || decoder.Decode(new(any)) != io.EOF || validatePoolConfig(cfg) != nil {
		return nil, errors.New("pool configuration rejected")
	}
	pool := newMasterPool(cfg, os.Stdout)
	ok := false
	defer func() {
		if !ok {
			pool.closeFiles()
		}
	}()
	for _, member := range pool.members {
		for i, path := range []string{member.config.ConfigPath, member.config.ResolversPath} {
			member.files[i], err = os.Open(path)
			if err != nil {
				return nil, errors.New("pool member memory unavailable")
			}
			stat, err := member.files[i].Stat()
			if err != nil || stat.Size() == 0 || stat.Size() > 262144 || !stat.Mode().IsRegular() {
				return nil, errors.New("pool member memory rejected")
			}
		}
		loaded, err := config.LoadClientConfigWithOverrides(member.config.ConfigPath,
			config.ClientConfigOverrides{ResolversFilePath: &member.config.ResolversPath})
		if err != nil {
			return nil, errors.New("pool member configuration rejected")
		}
		expected, _ := poolEndpoint(member.config.Listen)
		ip, err := netip.ParseAddr(loaded.ListenIP)
		if err != nil || ip.Unmap() != expected.Addr() || loaded.ListenPort != int(expected.Port()) ||
			!loaded.SOCKS5Auth || loaded.SOCKS5User != member.config.Username ||
			loaded.SOCKS5Pass != member.config.Password || loaded.LocalDNSEnabled || loaded.ProtocolType != "SOCKS5" ||
			loaded.SaveMTUServersToFile {
			return nil, errors.New("pool child listener or disk policy rejected")
		}
	}
	ok = true
	return pool, nil
}

func newMasterPool(cfg poolConfig, output io.Writer) *masterPool {
	pool := &masterPool{config: cfg, output: output, streams: make(map[net.Conn]struct{})}
	for _, member := range cfg.Members {
		pool.members = append(pool.members, &poolMember{config: member})
	}
	return pool
}

func (pool *masterPool) closeFiles() {
	for _, member := range pool.members {
		for _, file := range member.files {
			if file != nil {
				file.Close()
			}
		}
	}
}

// Called with mu held. Only member IDs and counts reach stdout; child log lines
// (which may include keys, domains or resolver addresses) are always discarded.
func (pool *masterPool) emitLocked() {
	ready, active := 0, 0
	ids := make([]string, 0, len(pool.members))
	now := time.Now()
	for _, member := range pool.members {
		if member.ready && !now.Before(member.cooling) {
			ready++
			ids = append(ids, member.config.ID)
		}
		active += member.active
	}
	status := struct {
		Ready  int      `json:"ready"`
		Total  int      `json:"total"`
		Active int      `json:"active"`
		IDs    []string `json:"readyIds"`
	}{ready, len(pool.members), active, ids}
	line, _ := json.Marshal(status)
	current := string(line)
	if current == pool.last {
		return
	}
	oldReady := strings.Contains(pool.last, `"ready":0,`) || pool.last == ""
	if pool.last == "" || oldReady != (ready == 0) {
		if ready == 0 {
			fmt.Fprintln(pool.output, "TUNA_MASTER_CONNECTING")
		} else {
			fmt.Fprintln(pool.output, "TUNA_MASTER_READY")
		}
	}
	fmt.Fprintln(pool.output, "TUNA_MASTER_POOL "+current)
	pool.last = current
}

func (pool *masterPool) markReady(member *poolMember, ready bool) {
	pool.mu.Lock()
	defer pool.mu.Unlock()
	member.ready = ready
	pool.emitLocked()
}

func (pool *masterPool) selectMember(tried map[*poolMember]bool) *poolMember {
	pool.mu.Lock()
	defer pool.mu.Unlock()
	now := time.Now()
	var selected *poolMember
	selectedIndex := 0
	for offset := range pool.members {
		index := (pool.next + offset) % len(pool.members)
		member := pool.members[index]
		if !member.ready || now.Before(member.cooling) || tried[member] {
			continue
		}
		if selected == nil || (pool.config.Strategy == "least_connections" && member.active < selected.active) {
			selected, selectedIndex = member, index
		}
		if pool.config.Strategy == "round_robin" {
			break
		}
	}
	if selected != nil {
		selected.active++ // Reserve before dialing to avoid concurrent selection skew.
		pool.next = (selectedIndex + 1) % len(pool.members)
		pool.emitLocked()
	}
	return selected
}

func (pool *masterPool) release(member *poolMember, failed bool) {
	pool.mu.Lock()
	defer pool.mu.Unlock()
	member.active--
	if failed {
		member.cooling = time.Now().Add(poolCooldown)
	}
	pool.emitLocked()
}

func (pool *masterPool) supervise(ctx context.Context, member *poolMember) {
	// On Linux Pdeathsig is tied to the thread that called clone, not just the
	// process. Keep that OS thread alive through Wait, including child shutdown.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	backoff := 250 * time.Millisecond
	for ctx.Err() == nil {
		executable, err := os.Executable()
		if err != nil {
			return
		}
		cmd := exec.Command(executable, "-config", "/proc/self/fd/3", "-resolvers", "/proc/self/fd/4", "-pool-child")
		cmd.ExtraFiles = []*os.File{member.files[0], member.files[1]}
		poolChildAttributes(cmd)
		cmd.Stdout = &poolMarkerWriter{pool: pool, member: member}
		cmd.Stderr = io.Discard
		started := time.Now()
		if cmd.Start() == nil {
			stopped := make(chan struct{})
			done := make(chan struct{})
			go func() {
				defer close(done)
				select {
				case <-stopped:
					return
				case <-ctx.Done():
					poolTerminateChild(cmd.Process)
				}
				timer := time.NewTimer(2 * time.Second)
				defer timer.Stop()
				select {
				case <-stopped:
				case <-timer.C:
					cmd.Process.Kill()
				}
			}()
			cmd.Wait()
			close(stopped)
			<-done
		}
		pool.markReady(member, false)
		if time.Since(started) > 30*time.Second {
			backoff = 250 * time.Millisecond
		}
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
		backoff = min(backoff*2, 15*time.Second)
	}
}

type poolMarkerWriter struct {
	pool   *masterPool
	member *poolMember
	line   []byte
	skip   bool
}

func (writer *poolMarkerWriter) Write(data []byte) (int, error) {
	for _, value := range data {
		if value == '\n' {
			if !writer.skip {
				line := strings.TrimSpace(string(writer.line))
				switch {
				case line == "TUNA_MASTER_READY":
					writer.pool.markReady(writer.member, true)
				case line == "TUNA_MASTER_CONNECTING" || strings.HasPrefix(line, "TUNA_MASTER_ERROR"):
					writer.pool.markReady(writer.member, false)
				}
			}
			writer.line, writer.skip = writer.line[:0], false
		} else if len(writer.line) < 4096 && !writer.skip {
			writer.line = append(writer.line, value)
		} else {
			writer.skip = true
		}
	}
	return len(data), nil
}

func runMasterPool(ctx context.Context, path string, validate bool) error {
	pool, err := loadPool(path)
	if err != nil {
		return err
	}
	defer pool.closeFiles()
	if validate {
		fmt.Println("TUNA_MASTER_VALID")
		return nil
	}
	listener, err := net.Listen("tcp", pool.config.Listen)
	if err != nil {
		return errors.New("pool listener unavailable")
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var workers sync.WaitGroup
	for _, member := range pool.members {
		workers.Add(1)
		go func() {
			defer workers.Done()
			pool.supervise(ctx, member)
		}()
	}
	err = pool.serve(ctx, listener)
	cancel()
	workers.Wait()
	return err
}

func (pool *masterPool) serve(ctx context.Context, listener net.Listener) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	defer listener.Close()
	finished := make(chan struct{})
	defer close(finished)
	go func() {
		ticker := time.NewTicker(250 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-finished:
				return
			case <-ctx.Done():
				listener.Close()
				pool.mu.Lock()
				for conn := range pool.streams {
					conn.Close()
				}
				pool.mu.Unlock()
				return
			case <-ticker.C:
				pool.mu.Lock()
				pool.emitLocked()
				pool.mu.Unlock()
			}
		}
	}()
	pool.mu.Lock()
	pool.emitLocked()
	pool.mu.Unlock()
	var workers sync.WaitGroup
	defer workers.Wait()
	slots := make(chan struct{}, poolMaxConnections)
	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			cancel()
			return errors.New("pool listener failed")
		}
		select {
		case slots <- struct{}{}:
		default:
			conn.Close()
			continue
		}
		pool.mu.Lock()
		pool.streams[conn] = struct{}{}
		pool.mu.Unlock()
		workers.Add(1)
		go func() {
			defer workers.Done()
			defer func() {
				conn.Close()
				pool.mu.Lock()
				delete(pool.streams, conn)
				pool.mu.Unlock()
				<-slots
			}()
			pool.handle(ctx, conn)
		}()
	}
}

func poolRead(conn net.Conn, length int) ([]byte, error) {
	result := make([]byte, length)
	_, err := io.ReadFull(conn, result)
	return result, err
}

func poolReply(conn net.Conn, code byte) {
	conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
}

func poolAuthenticate(conn net.Conn, username, password string) bool {
	header, err := poolRead(conn, 2)
	if err != nil || header[0] != 5 || header[1] == 0 {
		return false
	}
	methods, err := poolRead(conn, int(header[1]))
	if err != nil {
		return false
	}
	if !bytes.Contains(methods, []byte{2}) {
		conn.Write([]byte{5, 255})
		return false
	}
	if _, err := conn.Write([]byte{5, 2}); err != nil {
		return false
	}
	header, err = poolRead(conn, 2)
	if err != nil || header[0] != 1 || header[1] == 0 {
		return false
	}
	user, err := poolRead(conn, int(header[1]))
	if err != nil {
		return false
	}
	length, err := poolRead(conn, 1)
	if err != nil || length[0] == 0 {
		return false
	}
	pass, err := poolRead(conn, int(length[0]))
	if err != nil {
		return false
	}
	if subtle.ConstantTimeCompare(user, []byte(username))&subtle.ConstantTimeCompare(pass, []byte(password)) != 1 {
		conn.Write([]byte{1, 1})
		return false
	}
	_, err = conn.Write([]byte{1, 0})
	return err == nil
}

func poolAddress(conn net.Conn, kind byte) ([]byte, error) {
	length := 0
	var address []byte
	switch kind {
	case 1:
		length = 4
	case 4:
		length = 16
	case 3:
		size, err := poolRead(conn, 1)
		if err != nil || size[0] == 0 {
			return nil, errors.New("invalid domain")
		}
		address, length = size, int(size[0])
	default:
		return nil, errors.New("unsupported address")
	}
	tail, err := poolRead(conn, length+2)
	return append(address, tail...), err
}

func poolConnect(ctx context.Context, member *poolMember, request []byte) (net.Conn, error) {
	conn, _, err := poolOpenChild(ctx, member, request)
	return conn, err
}

func poolOpenChild(ctx context.Context, member *poolMember, request []byte) (net.Conn, []byte, error) {
	dialer := net.Dialer{Timeout: poolConnectLimit}
	conn, err := dialer.DialContext(ctx, "tcp", member.config.Listen)
	if err != nil {
		return nil, nil, err
	}
	ok := false
	defer func() {
		if !ok {
			conn.Close()
		}
	}()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	deadline, exists := ctx.Deadline()
	if !exists || time.Until(deadline) > poolConnectLimit {
		deadline = time.Now().Add(poolConnectLimit)
	}
	conn.SetDeadline(deadline)
	if _, err = conn.Write([]byte{5, 1, 2}); err != nil {
		return nil, nil, err
	}
	response, err := poolRead(conn, 2)
	if err != nil || !bytes.Equal(response, []byte{5, 2}) {
		return nil, nil, errors.New("child authentication unavailable")
	}
	auth := append([]byte{1, byte(len(member.config.Username))}, []byte(member.config.Username)...)
	auth = append(auth, byte(len(member.config.Password)))
	auth = append(auth, []byte(member.config.Password)...)
	if _, err = conn.Write(auth); err != nil {
		return nil, nil, err
	}
	response, err = poolRead(conn, 2)
	if err != nil || !bytes.Equal(response, []byte{1, 0}) {
		return nil, nil, errors.New("child authentication rejected")
	}
	if _, err = conn.Write(request); err != nil {
		return nil, nil, err
	}
	response, err = poolRead(conn, 4)
	if err != nil || response[0] != 5 || response[2] != 0 || response[1] > 8 {
		return nil, nil, errors.New("child remote request failed")
	}
	address, err := poolAddress(conn, response[3])
	if err != nil {
		return nil, nil, err
	}
	if response[1] != 0 {
		return nil, nil, poolDestinationError(response[1])
	}
	if ctx.Err() != nil {
		return nil, nil, ctx.Err()
	}
	conn.SetDeadline(time.Time{})
	ok = true
	return conn, append(response, address...), nil
}

func (pool *masterPool) handle(ctx context.Context, frontend net.Conn) {
	lifetime := ctx
	ctx, cancel := context.WithTimeout(ctx, poolHandshakeLimit)
	defer cancel()
	stop := context.AfterFunc(ctx, func() { frontend.Close() })
	defer stop()
	frontend.SetDeadline(time.Now().Add(poolHandshakeLimit))
	if !poolAuthenticate(frontend, pool.config.Username, pool.config.Password) {
		return
	}
	header, err := poolRead(frontend, 4)
	if err != nil || header[0] != 5 || header[2] != 0 {
		return
	}
	if header[1] != 1 && header[1] != 3 {
		poolReply(frontend, 7)
		return
	}
	address, err := poolAddress(frontend, header[3])
	if err != nil {
		poolReply(frontend, 8)
		return
	}
	if header[1] == 3 {
		pool.handleUDP(lifetime, ctx, frontend, header, address, stop)
		return
	}
	if len(address) < 2 || address[len(address)-2] == 0 && address[len(address)-1] == 0 {
		poolReply(frontend, 1)
		return
	}
	request := append(header, address...)
	tried := make(map[*poolMember]bool, len(pool.members))
	var backend net.Conn
	var selected *poolMember
	lastReply := byte(1)
	for ctx.Err() == nil {
		selected = pool.selectMember(tried)
		if selected == nil {
			poolReply(frontend, lastReply)
			return
		}
		tried[selected] = true
		backend, err = poolConnect(ctx, selected, request)
		if err == nil {
			break
		}
		var destination poolDestinationError
		failedChild := !errors.As(err, &destination)
		if !failedChild {
			lastReply = byte(destination)
		}
		pool.release(selected, failedChild)
	}
	if backend == nil {
		return
	}
	defer backend.Close()
	defer pool.release(selected, false)
	stopLifetime := context.AfterFunc(lifetime, func() { frontend.Close(); backend.Close() })
	defer stopLifetime()
	// No application bytes have been read at this point. Once success reaches
	// the frontend, this stream stays pinned; failure never causes replay.
	if !stop() || ctx.Err() != nil {
		return
	}
	cancel()
	frontend.SetDeadline(time.Time{})
	poolReply(frontend, 0)
	done := make(chan struct{})
	go func() {
		io.Copy(backend, frontend)
		if tcp, ok := backend.(*net.TCPConn); ok {
			tcp.CloseWrite()
		}
		close(done)
	}()
	io.Copy(frontend, backend)
	frontend.Close()
	backend.Close()
	<-done
}
