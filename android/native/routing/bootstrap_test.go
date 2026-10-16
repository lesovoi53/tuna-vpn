package main

import (
    "context"
    "encoding/binary"
    "io"
    "net"
    "testing"
    "time"
)

func TestBootstrapUsesExplicitTCPResolver(t *testing.T) {
    previous := net.DefaultResolver
    defer func() { net.DefaultResolver = previous }()
    listener, err := net.Listen("tcp", "127.0.0.1:0")
    if err != nil { t.Fatal(err) }
    defer listener.Close()
    done := make(chan error, 1)
    go func() {
        connection, e := listener.Accept()
        if e != nil { done <- e; return }
        defer connection.Close()
        connection.SetDeadline(time.Now().Add(3*time.Second))
        var size uint16
        e = binary.Read(connection, binary.BigEndian, &size)
        if e != nil { done <- e; return }
        query := make([]byte, size)
        if _, e = io.ReadFull(connection, query); e != nil { done <- e; return }
        // Preserve exactly the question, not optional EDNS additional records.
        end := 12
        for end < len(query) && query[end] != 0 { end += int(query[end]) + 1 }
        end += 5
        response := append([]byte(nil), query[:end]...)
        response[2], response[3] = 0x81, 0x80
        response[6], response[7], response[10], response[11] = 0, 1, 0, 0
        response = append(response, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 192, 0, 2, 55)
        e = binary.Write(connection, binary.BigEndian, uint16(len(response)))
        if e == nil { _, e = connection.Write(response) }
        done <- e
    }()
    if err = configureBootstrapResolver("tcp://"+listener.Addr().String()); err != nil { t.Fatal(err) }
    ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
    defer cancel()
    addresses, err := net.DefaultResolver.LookupIP(ctx, "ip4", "bootstrap.invalid")
    if err != nil || len(addresses) != 1 || addresses[0].String() != "192.0.2.55" { t.Fatalf("wrong bootstrap result: %v %v", addresses, err) }
    if err = <-done; err != nil { t.Fatal(err) }
}

func TestInvalidBootstrapKeepsResolverUnchanged(t *testing.T) {
    previous := net.DefaultResolver
    for _, value := range []string{"tcp://dns.example", "udp://1.1.1.1", "tcp://127.0.0.1:0", "tcp://u:p@127.0.0.1", "tcp://127.0.0.1/path"} {
        if configureBootstrapResolver(value) == nil { t.Fatalf("accepted %q", value) }
        if net.DefaultResolver != previous { t.Fatal("invalid setting replaced resolver") }
    }
}
