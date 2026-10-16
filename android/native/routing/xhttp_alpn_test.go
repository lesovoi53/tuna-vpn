package splithttp

import (
    "crypto/sha256"
    "net/http"
    "net/http/httptest"
    "testing"
    "time"

    xnet "github.com/xtls/xray-core/common/net"
    "github.com/xtls/xray-core/transport/internet"
    "github.com/xtls/xray-core/transport/internet/tls"
)

// A server supporting both HTTP versions must not negotiate h2 with an XHTTP
// client whose imported link explicitly requests HTTP/1.1.
func TestTunaXHTTPExplicitHTTP1ALPN(t *testing.T) {
  for _, protocol := range []string{"http/1.1", "h2"} {
    for _, fingerprint := range []string{"safari", "firefox", "edge", "ios"} {
        t.Run(protocol+"/"+fingerprint, func(t *testing.T) {
            server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
                want := 1
                if protocol == "h2" { want = 2 }
                if r.ProtoMajor != want { t.Errorf("unexpected HTTP version %s", r.Proto) }
                w.WriteHeader(http.StatusNoContent)
            }))
            server.EnableHTTP2 = true
            server.StartTLS()
            defer server.Close()
            pin := sha256.Sum256(server.Certificate().Raw)
            dest, err := xnet.ParseDestination("tcp:" + server.Listener.Addr().String())
            if err != nil { t.Fatal(err) }
            settings := &internet.MemoryStreamConfig{
                ProtocolName: "splithttp", ProtocolSettings: &Config{},
                SecurityType: "tls", SecuritySettings: &tls.Config{
                    PinnedPeerCertSha256: [][]byte{pin[:]},
                    NextProtocol: []string{protocol}, Fingerprint: fingerprint,
                },
            }
            client := createHTTPClient(dest, settings).(*DefaultDialerClient).client
            client.Timeout = 3 * time.Second
            response, err := client.Get(server.URL)
            if err != nil { t.Fatal(err) }
            defer response.Body.Close()
            if response.StatusCode != http.StatusNoContent { t.Fatalf("HTTP status %d", response.StatusCode) }
        })
    }
  }
}
