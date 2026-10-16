package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// Local-only fixture for routing assertions. It never opens an upstream socket.
type testChannel struct {
	listener    net.Listener
	dnsRequests atomic.Int32
}

func startTestChannel(t *testing.T, address string, id int) *testChannel {
	t.Helper()
	listener, err := net.Listen("tcp", address)
	if err != nil {
		t.Fatal(err)
	}
	fixture := &testChannel{listener: listener}
	var workers sync.WaitGroup
	var connections sync.Map
	workers.Add(1)
	go func() {
		defer workers.Done()
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			connections.Store(conn, true)
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer connections.Delete(conn)
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(5 * time.Second))
				if !authenticateSOCKS(conn, localAuth{Username: fmt.Sprintf("channel-%d", id), Password: fmt.Sprintf("pass-%d", id)}) {
					return
				}
				var header [4]byte
				if _, err := io.ReadFull(conn, header[:]); err != nil || header[0] != 5 || header[1] != 1 || header[2] != 0 {
					return
				}
				length := 0
				switch header[3] {
				case 1:
					length = 4
				case 4:
					length = 16
				case 3:
					var size [1]byte
					if _, err := io.ReadFull(conn, size[:]); err != nil {
						return
					}
					length = int(size[0])
				default:
					return
				}
				destination := make([]byte, length+2)
				if _, err := io.ReadFull(conn, destination); err != nil {
					return
				}
				if _, err := conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); err != nil {
					return
				}
				if binary.BigEndian.Uint16(destination[length:]) == 53 {
					fixture.serveDNS(conn)
					return
				}
				request, err := http.ReadRequest(bufio.NewReader(conn))
				if err != nil {
					return
				}
				request.Body.Close()
				body := strconv.Itoa(id)
				fmt.Fprintf(conn, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(body), body)
			}()
		}
	}()
	t.Cleanup(func() {
		listener.Close()
		connections.Range(func(key, value any) bool { key.(net.Conn).Close(); return true })
		workers.Wait()
	})
	return fixture
}

func (fixture *testChannel) serveDNS(conn net.Conn) {
	for {
		var size [2]byte
		if _, err := io.ReadFull(conn, size[:]); err != nil {
			return
		}
		data := make([]byte, binary.BigEndian.Uint16(size[:]))
		if _, err := io.ReadFull(conn, data); err != nil {
			return
		}
		var query dnsmessage.Message
		if err := query.Unpack(data); err != nil {
			return
		}
		fixture.dnsRequests.Add(1)
		response := dnsmessage.Message{Header: dnsmessage.Header{ID: query.ID, Response: true, RecursionDesired: query.RecursionDesired, RecursionAvailable: true}, Questions: query.Questions}
		for _, question := range query.Questions {
			if question.Type == dnsmessage.TypeA {
				response.Answers = append(response.Answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, 42}}})
			}
		}
		data, err := response.Pack()
		if err != nil {
			return
		}
		binary.BigEndian.PutUint16(size[:], uint16(len(data)))
		if _, err := conn.Write(append(size[:], data...)); err != nil {
			return
		}
	}
}
