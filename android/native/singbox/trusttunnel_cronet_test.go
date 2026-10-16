//go:build with_trusttunnel_cronet

package trusttunnel

import (
    "context"
    "io"
    "net/http"
    "testing"
    "time"
)

func TestTunaCronetIdleUploadStopsOnCancellation(t *testing.T) {
    reader, writer := io.Pipe()
    defer writer.Close()
    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()
    done := make(chan struct{})
    go func() { tunaCopyRequestBody(ctx, io.Discard, reader); close(done) }()
    cancel()
    select {
    case <-done:
    case <-time.After(time.Second): t.Fatal("idle CONNECT upload survived cancellation")
    }
}

func TestTunaCronetRejectsRequestsAfterClose(t *testing.T) {
    transport := &cronetRoundTripper{closed: true}
    request, _ := http.NewRequestWithContext(context.Background(), http.MethodConnect, "https://example.invalid", nil)
    if _, err := transport.RoundTrip(request); err == nil { t.Fatal("closed transport accepted a request") }
    if fd := transport.tcpDialer(context.Background(), nil)("127.0.0.1", 443); fd >= 0 { t.Fatal("closed transport accepted a TCP dial") }
    if fd, _, _, _ := transport.udpDialer(context.Background(), nil)("127.0.0.1", 443); fd >= 0 { t.Fatal("closed transport accepted a UDP dial") }
    // Must be idempotent without accessing the destroyed engine.
    if err := transport.Close(); err != nil { t.Fatal(err) }
}
