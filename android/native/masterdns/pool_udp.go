package main

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"
)

// A SOCKS UDP association is a DNS-only transport, matching pinned upstream.
// No destination is dialed or resolved here: every datagram goes to its one
// authenticated child, for the lifetime of the original TCP control channel.
func poolUDPAddress(kind byte, address []byte) (netip.AddrPort, bool) {
	var ip netip.Addr
	if kind == 1 && len(address) == 6 {
		ip = netip.AddrFrom4([4]byte(address[:4]))
	} else if kind == 4 && len(address) == 18 {
		ip = netip.AddrFrom16([16]byte(address[:16]))
	} else {
		return netip.AddrPort{}, false
	}
	return netip.AddrPortFrom(ip.Unmap(), binary.BigEndian.Uint16(address[len(address)-2:])), true
}

func poolUDPWireAddress(endpoint netip.AddrPort) []byte {
	endpoint = netip.AddrPortFrom(endpoint.Addr().Unmap(), endpoint.Port())
	wire := []byte{1}
	if endpoint.Addr().Is4() {
		ip := endpoint.Addr().As4()
		wire = append(wire, ip[:]...)
	} else {
		wire[0] = 4
		ip := endpoint.Addr().As16()
		wire = append(wire, ip[:]...)
	}
	return binary.BigEndian.AppendUint16(wire, endpoint.Port())
}

func poolUDPPacket(packet []byte) ([]byte, bool) {
	if len(packet) < 4 || len(packet) > 4096 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 {
		return nil, false
	}
	offset := 0
	switch packet[3] {
	case 1:
		offset = 10
	case 4:
		offset = 22
	case 3:
		if len(packet) < 5 || packet[4] == 0 {
			return nil, false
		}
		offset = 7 + int(packet[4])
	default:
		return nil, false
	}
	if len(packet) < offset+12 || binary.BigEndian.Uint16(packet[offset-2:offset]) != 53 {
		return nil, false
	}
	return packet[offset:], true
}

func poolDNSQuestion(message []byte) (string, bool) {
	if len(message) < 17 || binary.BigEndian.Uint16(message[4:6]) != 1 {
		return "", false
	}
	offset, end, steps := 12, 0, 0
	var labels []string
	for {
		steps++
		if steps > 128 || offset >= len(message) {
			return "", false
		}
		length := int(message[offset])
		if length&0xc0 == 0xc0 {
			if offset+1 >= len(message) {
				return "", false
			}
			if end == 0 {
				end = offset + 2
			}
			offset = (length&0x3f)<<8 | int(message[offset+1])
			continue
		}
		offset++
		if length > 63 || offset+length > len(message) {
			return "", false
		}
		if length == 0 {
			if end == 0 {
				end = offset
			}
			break
		}
		labels = append(labels, strings.ToLower(string(message[offset:offset+length])))
		offset += length
	}
	if end+4 > len(message) {
		return "", false
	}
	return string([]byte{message[2] & 0x78}) + strings.Join(labels, ".") + "\x00" + string(message[end:end+4]), true
}

func (pool *masterPool) handleUDP(lifetime, handshake context.Context, frontend net.Conn, header, address []byte, stopHandshake func() bool) {
	requested, ok := poolUDPAddress(header[3], address)
	peer, peerOK := frontend.RemoteAddr().(*net.TCPAddr)
	if !ok || !peerOK {
		poolReply(frontend, 8)
		return
	}
	peerIP, ok := netip.AddrFromSlice(peer.IP)
	if !ok || !peerIP.IsLoopback() || (!requested.Addr().IsUnspecified() && requested.Addr() != peerIP.Unmap()) {
		poolReply(frontend, 2)
		return
	}
	local := frontend.LocalAddr().(*net.TCPAddr)
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: local.IP})
	if err != nil {
		poolReply(frontend, 1)
		return
	}
	defer relay.Close()
	tried := map[*poolMember]bool{}
	var control net.Conn
	var remote *net.UDPConn
	var bound []byte
	var selected *poolMember
	for handshake.Err() == nil {
		selected = pool.selectMember(tried)
		if selected == nil {
			poolReply(frontend, 1)
			return
		}
		tried[selected] = true
		childListen, _ := poolEndpoint(selected.config.Listen)
		remote, err = net.ListenUDP("udp", net.UDPAddrFromAddrPort(netip.AddrPortFrom(childListen.Addr(), 0)))
		if err != nil {
			pool.release(selected, false)
			poolReply(frontend, 1)
			return
		}
		request := append([]byte{5, 3, 0}, poolUDPWireAddress(remote.LocalAddr().(*net.UDPAddr).AddrPort())...)
		control, bound, err = poolOpenChild(handshake, selected, request)
		if err == nil {
			break
		}
		remote.Close()
		var destination poolDestinationError
		pool.release(selected, !errors.As(err, &destination))
	}
	if control == nil {
		return
	}
	defer control.Close()
	defer remote.Close()
	defer pool.release(selected, false)
	childAddr, ok := poolUDPAddress(bound[3], bound[4:])
	expected, _ := poolEndpoint(selected.config.Listen)
	if !ok || !childAddr.Addr().IsLoopback() || childAddr.Addr() != expected.Addr() || childAddr.Port() == 0 {
		poolReply(frontend, 1)
		return
	}
	if !stopHandshake() || handshake.Err() != nil {
		return
	}
	frontend.SetDeadline(time.Time{})
	endpoint := relay.LocalAddr().(*net.UDPAddr).AddrPort()
	reply := append([]byte{5, 0, 0}, poolUDPWireAddress(endpoint)...)
	if _, err = frontend.Write(reply); err != nil {
		return
	}
	ctx, cancel := context.WithCancel(lifetime)
	defer cancel()
	stop := context.AfterFunc(ctx, func() {
		frontend.Close()
		control.Close()
		relay.Close()
		remote.Close()
	})
	defer stop()
	var workers sync.WaitGroup
	defer workers.Wait()
	// Either control EOF shuts down both UDP sockets immediately, even if the
	// peer never sends a datagram. Control payload is forbidden by SOCKS5.
	for _, connection := range []net.Conn{frontend, control} {
		workers.Add(1)
		go func() {
			defer workers.Done()
			var buffer [1]byte
			connection.Read(buffer[:])
			cancel()
		}()
	}
	var state sync.Mutex
	var origin *net.UDPAddr
	type pendingQuery struct {
		at       time.Time
		question string
	}
	pending := map[uint16]pendingQuery{}
	workers.Add(1)
	go func() {
		defer workers.Done()
		defer cancel()
		buffer := make([]byte, 4097)
		for ctx.Err() == nil {
			remote.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, source, err := remote.ReadFromUDPAddrPort(buffer)
			if err != nil {
				return
			}
			source = netip.AddrPortFrom(source.Addr().Unmap(), source.Port())
			if source != childAddr {
				continue
			}
			dns, valid := poolUDPPacket(buffer[:n])
			if !valid || dns[2]&0x80 == 0 {
				continue
			}
			question, valid := poolDNSQuestion(dns)
			if !valid {
				continue
			}
			id := binary.BigEndian.Uint16(dns[:2])
			state.Lock()
			query, exists := pending[id]
			destination := origin
			if exists && query.question == question {
				delete(pending, id)
			}
			state.Unlock()
			if exists && query.question == question && time.Since(query.at) < 30*time.Second && destination != nil {
				relay.WriteToUDP(buffer[:n], destination)
			}
		}
	}()
	buffer := make([]byte, 4097)
	for ctx.Err() == nil {
		relay.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, sender, err := relay.ReadFromUDP(buffer)
		if err != nil {
			break
		}
		dns, valid := poolUDPPacket(buffer[:n])
		if !valid || dns[2]&0x80 != 0 || sender.AddrPort().Addr().Unmap() != peerIP.Unmap() ||
			(requested.Port() != 0 && sender.Port != int(requested.Port())) {
			continue
		}
		question, valid := poolDNSQuestion(dns)
		if !valid {
			continue
		}
		state.Lock()
		if origin == nil {
			origin = sender
		}
		sameOrigin := sender.AddrPort() == origin.AddrPort()
		if sameOrigin {
			now := time.Now()
			for id, query := range pending {
				if now.Sub(query.at) >= 30*time.Second {
					delete(pending, id)
				}
			}
			if len(pending) < 256 {
				pending[binary.BigEndian.Uint16(dns[:2])] = pendingQuery{now, question}
			} else {
				sameOrigin = false
			}
		}
		state.Unlock()
		if sameOrigin {
			if _, err = remote.WriteToUDPAddrPort(buffer[:n], childAddr); err != nil {
				break
			}
		}
	}
	cancel()
	// Ensure the cancellation callback completes before its deferred stop and
	// worker Wait, including the already-cancelled parent context case.
	frontend.Close()
	control.Close()
	relay.Close()
	remote.Close()
}
