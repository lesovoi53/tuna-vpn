package dns

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	stdnet "net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	fdns "github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport"
	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/http2"
)

type tunaEndpointBootstrap struct {
	tag   string
	host  string
	query func(context.Context) ([]net.IP, uint32, error)
}

func (*tunaEndpointBootstrap) Name() string         { return "bootstrap" }
func (*tunaEndpointBootstrap) IsDisableCache() bool { return false }
func (s *tunaEndpointBootstrap) QueryIP(ctx context.Context, host string, _ fdns.IPOption) ([]net.IP, uint32, error) {
	s.host = host
	if inbound := session.InboundFromContext(ctx); inbound != nil {
		s.tag = inbound.Tag
	}
	return s.query(ctx)
}

func TestTunaDoHBootstrapKeepsEndpointAndPoolRoute(t *testing.T) {
	for _, tag := range []string{"tuna-dns-remote", "tuna-dns-domestic"} {
		bootstrap := &tunaEndpointBootstrap{query: tunaAnswer}
		ctx := session.ContextWithInbound(context.Background(), &session.Inbound{Tag: tag})
		config := &tls.Config{ServerName: "doh.example"}
		var dialed bool
		dial := tunaDoHBootstrapDial(bootstrap, "doh.example", func(got context.Context, network, address string, gotConfig *tls.Config) (net.Conn, error) {
			dialed = true
			if address != "192.0.2.42:8443" || network != "tcp" || gotConfig != config || session.InboundFromContext(got).Tag != tag {
				t.Fatal("DoH identity, address, or route changed")
			}
			return nil, nil
		})
		if _, err := dial(ctx, "tcp", "doh.example:8443", config); err != nil {
			t.Fatal(err)
		}
		if !dialed || bootstrap.host != "doh.example" || bootstrap.tag != tag {
			t.Fatal("bootstrap escaped endpoint or pool")
		}
	}
}

func TestTunaDoHBootstrapFailureNeverFallsBackOrIgnoresDeadline(t *testing.T) {
	for _, query := range []func(context.Context) ([]net.IP, uint32, error){
		func(context.Context) ([]net.IP, uint32, error) { return nil, 0, errors.New("bootstrap unavailable") },
		func(context.Context) ([]net.IP, uint32, error) { return nil, 0, nil },
		func(ctx context.Context) ([]net.IP, uint32, error) { <-ctx.Done(); return nil, 0, ctx.Err() },
	} {
		bootstrap := &tunaEndpointBootstrap{query: query}
		dial := tunaDoHBootstrapDial(bootstrap, "doh.example", func(context.Context, string, string, *tls.Config) (net.Conn, error) {
			t.Fatal("unexpected direct or unresolved fallback")
			return nil, nil
		})
		ctx, cancel := context.WithTimeout(context.Background(), 25*time.Millisecond)
		start := time.Now()
		_, err := dial(ctx, "tcp", "doh.example:443", &tls.Config{})
		cancel()
		if err == nil || time.Since(start) > time.Second {
			t.Fatal("bootstrap failure/deadline lost")
		}
	}
}

func TestTunaDoHBootstrapRejectsRecursiveResolverAndInvalidEndpoint(t *testing.T) {
	for _, pair := range [][2]string{
		{"https://doh.example/dns-query", "tcp://bootstrap.example:53"},
		{"https://doh.example/dns-query", "tcp+local://1.1.1.1:53"},
		{"http://doh.example/dns-query", "tcp://1.1.1.1:53"},
		{"https://user@doh.example/dns-query", "tcp://1.1.1.1:53"},
	} {
		bytes, _ := json.Marshal(map[string]string{"endpoint": pair[0], "bootstrap": pair[1]})
		if _, err := newTunaDoHServer("tuna-doh://"+base64.RawURLEncoding.EncodeToString(bytes), nil, false, false, 0, nil); err == nil {
			t.Fatal("unsafe endpoint accepted")
		}
	}
}

// Xray constructs its own uTLS config. Test its real verification rather than
// replacing DialTLSContext or enabling InsecureSkipVerify. Fallback roots are
// process-global, so install a generated test CA only in an isolated subprocess.
func TestTunaDoHVerifiedTLSAndPoolIsolation(t *testing.T) {
	if os.Getenv("TUNA_DOH_TLS_TEST_CHILD") != "1" {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestTunaDoHVerifiedTLSAndPoolIsolation$", "-test.v")
		cmd.Env = append(os.Environ(), "TUNA_DOH_TLS_TEST_CHILD=1", "GODEBUG=x509usefallbackroots=1")
		output, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("isolated real DoH tests: %v\n%s", err, output)
		}
		t.Logf("%s", output)
		return
	}
	ca, key := tunaTestCA(t)
	roots := x509.NewCertPool()
	roots.AddCert(ca)
	x509.SetFallbackRoots(roots)
	t.Run("coalesced-waiter-keeps-own-deadline", func(t *testing.T) {
		started := make(chan struct{})
		release := make(chan struct{})
		defer close(release)
		fixture := tunaNewWireFixture(t, tunaTestCertificate(t, ca, key, "same-doh.invalid", time.Now().Add(time.Hour)), func(group int, _ string) {
			if group == 0 {
				close(started)
				<-release
			}
		})
		pool := fixture.pool(t, tunaPoolContext(context.Background()), "remote")
		leader, cancelLeader := context.WithCancel(tunaWireContext("remote"))
		defer cancelLeader()
		done := make(chan error, 1)
		go func() {
			_, _, err := pool.QueryIP(leader, "coalesced.invalid", fdns.IPOption{IPv4Enable: true})
			done <- err
		}()
		select {
		case <-started:
		case <-time.After(3 * time.Second):
			t.Fatal("leader did not reach DoH")
		}
		waiter, cancelWaiter := context.WithTimeout(tunaWireContext("remote"), 40*time.Millisecond)
		defer cancelWaiter()
		start := time.Now()
		_, _, err := pool.QueryIP(waiter, "coalesced.invalid", fdns.IPOption{IPv4Enable: true})
		if !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 500*time.Millisecond {
			t.Fatalf("coalesced waiter deadline: %v (%v)", err, time.Since(start))
		}
		cancelLeader()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("leader did not cancel")
		}
		if fixture.requests[0].Load() != 1 {
			t.Fatal("coalesced request duplicated")
		}
	})
	for _, scenario := range []string{"valid", "wrong-host", "untrusted", "expired"} {
		t.Run(scenario, func(t *testing.T) {
			issuer, signer := ca, key
			host, expiry := "same-doh.invalid", time.Now().Add(time.Hour)
			if scenario == "wrong-host" {
				host = "other.invalid"
			}
			if scenario == "untrusted" {
				issuer, signer = tunaTestCA(t)
			}
			if scenario == "expired" {
				expiry = time.Now().Add(-time.Hour)
			}
			certificate := tunaTestCertificate(t, issuer, signer, host, expiry)
			fixture := tunaNewWireFixture(t, certificate, nil)
			pool := fixture.pool(t, tunaPoolContext(context.Background()), "remote")
			ips, _, err := pool.QueryIP(tunaWireContext("remote"), "answer.invalid", fdns.IPOption{IPv4Enable: true})
			if scenario == "valid" {
				if err != nil || len(ips) != 1 || ips[0].String() != "192.0.2.42" {
					t.Fatalf("verified DoH answer: %v %v", ips, err)
				}
				if fixture.requests[0].Load() != 1 {
					t.Fatal("no real HTTP/2 request")
				}
			} else {
				if err == nil || len(ips) != 0 {
					t.Fatalf("invalid certificate accepted: %v %v", ips, err)
				}
				// QueryIP intentionally normalizes transport errors to record-not-found.
				// Inspect a second real handshake through the unchanged dial closure
				// to distinguish certificate rejection from unrelated I/O failure.
				ctx, cancel := context.WithTimeout(tunaWireContext("remote"), time.Second)
				defer cancel()
				dial := pool.children[0].(*DoHNameServer).httpClient.Transport.(*http2.Transport).DialTLSContext
				conn, tlsErr := dial(ctx, "tcp", "same-doh.invalid:443", &tls.Config{ServerName: "same-doh.invalid"})
				if conn != nil {
					conn.Close()
				}
				if tlsErr == nil || !strings.Contains(tlsErr.Error(), "certificate") {
					t.Fatalf("not a certificate rejection: %v", tlsErr)
				}
				if fixture.requests[0].Load() != 0 {
					t.Fatal("HTTP request reached server despite invalid certificate")
				}
			}
			if fixture.bootstrap[0].Load() == 0 || fixture.bootstrap[1].Load() != 0 || fixture.requests[1].Load() != 0 {
				t.Fatal("bootstrap missing or crossed pool")
			}
		})
	}
	t.Run("concurrent-warm-cache-and-cancellation", func(t *testing.T) {
		started := make(chan struct{})
		release := make(chan struct{})
		defer close(release)
		fixture := tunaNewWireFixture(t, tunaTestCertificate(t, ca, key, "same-doh.invalid", time.Now().Add(time.Hour)), func(group int, name string) {
			if group == 0 && name == "cancel.invalid." {
				close(started)
				<-release
			}
		})
		registry := tunaPoolContext(context.Background())
		remote, domestic := fixture.pool(t, registry, "remote"), fixture.pool(t, registry, "domestic")
		check := func(pool Server, group, domain, want string) error {
			ips, _, err := pool.QueryIP(tunaWireContext(group), domain, fdns.IPOption{IPv4Enable: true})
			if err != nil || len(ips) != 1 || ips[0].String() != want {
				return fmt.Errorf("%s: %v %v", group, ips, err)
			}
			return nil
		}
		// Simultaneous identical endpoint and question, with different wire answers.
		for round := 0; round < 3; round++ {
			results := make(chan error, 2)
			go func() { results <- check(remote, "remote", "shared.invalid", "192.0.2.42") }()
			go func() { results <- check(domestic, "domestic", "shared.invalid", "192.0.2.99") }()
			for i := 0; i < 2; i++ {
				if err := <-results; err != nil {
					t.Fatal(err)
				}
			}
		}
		if fixture.requests[0].Load() != 1 || fixture.requests[1].Load() != 1 {
			t.Fatal("warm caches missed or shared across pools")
		}
		// A blocked tag must not obtain a cached answer from either pool.
		for _, pool := range []Server{remote, domestic} {
			if _, _, err := pool.QueryIP(tunaWireContext("block"), "shared.invalid", fdns.IPOption{IPv4Enable: true}); err == nil {
				t.Fatal("BLOCK read warm pool cache")
			}
		}
		ctx, cancel := context.WithCancel(tunaWireContext("remote"))
		defer cancel()
		cancelled := make(chan error, 1)
		go func() {
			_, _, err := remote.QueryIP(ctx, "cancel.invalid", fdns.IPOption{IPv4Enable: true})
			cancelled <- err
		}()
		select {
		case <-started:
		case <-time.After(3 * time.Second):
			t.Fatal("remote query never reached wire")
		}
		// The domestic same-name request must complete while the remote is pending.
		if err := check(domestic, "domestic", "cancel.invalid", "192.0.2.99"); err != nil {
			t.Fatal(err)
		}
		cancel()
		select {
		case err := <-cancelled:
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("cancellation lost: %v", err)
			}
		case <-time.After(time.Second):
			t.Fatal("cancel did not return")
		}
		if err := check(remote, "remote", "shared.invalid", "192.0.2.42"); err != nil {
			t.Fatal(err)
		}
		if err := check(domestic, "domestic", "cancel.invalid", "192.0.2.99"); err != nil {
			t.Fatal(err)
		}
		if fixture.requests[0].Load() != 2 || fixture.requests[1].Load() != 2 {
			t.Fatal("unexpected wire request after cancellation/cache hit")
		}
		remote.mu.Lock()
		failures := remote.health[0].failures
		remote.mu.Unlock()
		if failures != 0 {
			t.Fatal("cancelled caller penalized resolver")
		}
	})
}

func tunaTestCA(t *testing.T) (*x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	private, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "localhost-only test CA"}, NotBefore: time.Now().Add(-24 * time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &private.PublicKey, private)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return certificate, private
}

func tunaTestCertificate(t *testing.T, ca *x509.Certificate, key *ecdsa.PrivateKey, hostname string, expiry time.Time) tls.Certificate {
	t.Helper()
	private, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(2), DNSNames: []string{hostname}, NotBefore: time.Now().Add(-24 * time.Hour), NotAfter: expiry, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, template, ca, &private.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: private}
}

func tunaWireContext(group string) context.Context {
	ctx := context.WithValue(context.Background(), core.XrayKey(1), new(core.Instance))
	return session.ContextWithInbound(ctx, &session.Inbound{Tag: "tuna-dns-" + group})
}

type tunaWireFixture struct {
	t           *testing.T
	addresses   [2][2]string
	bootstrap   [2]atomic.Int32
	requests    [2]atomic.Int32
	mu          sync.Mutex
	connections []stdnet.Conn
}

// Only the dispatcher boundary is a fixture: all DNS framing, bootstrap,
// caches, pool selection, uTLS, HTTP/2 and cancellation are production code.
// Unknown tags/destinations are rejected; no OS DNS or external dial is possible.
func (*tunaWireFixture) Type() interface{} { return routing.DispatcherType() }
func (*tunaWireFixture) Start() error      { return nil }
func (*tunaWireFixture) Close() error      { return nil }
func (*tunaWireFixture) DispatchLink(context.Context, net.Destination, *transport.Link) error {
	return errors.New("unexpected DispatchLink")
}
func (f *tunaWireFixture) Dispatch(ctx context.Context, dest net.Destination) (*transport.Link, error) {
	inbound := session.InboundFromContext(ctx)
	group := -1
	if inbound != nil {
		if inbound.Tag == "tuna-dns-remote" {
			group = 0
		}
		if inbound.Tag == "tuna-dns-domestic" {
			group = 1
		}
	}
	kind := -1
	if dest.NetAddr() == "192.0.2.90:53" {
		kind = 0
	}
	if dest.NetAddr() == "127.0.0.1:443" {
		kind = 1
	}
	if group < 0 || kind < 0 {
		f.t.Errorf("unapproved dispatch: tag=%v destination=%s", inbound, dest)
		return nil, errors.New("unapproved dispatch")
	}
	conn, err := (&stdnet.Dialer{}).DialContext(ctx, "tcp", f.addresses[group][kind])
	if err != nil {
		return nil, err
	}
	f.mu.Lock()
	f.connections = append(f.connections, conn)
	f.mu.Unlock()
	return &transport.Link{Reader: buf.NewReader(conn), Writer: buf.NewWriter(conn)}, nil
}

func tunaWireAnswer(data []byte, ip [4]byte) ([]byte, string, error) {
	var message dnsmessage.Message
	if err := message.Unpack(data); err != nil {
		return nil, "", err
	}
	if len(message.Questions) != 1 {
		return nil, "", errors.New("expected one DNS question")
	}
	q := message.Questions[0]
	message.Response = true
	message.RecursionAvailable = true
	message.Additionals = nil
	if q.Type == dnsmessage.TypeA {
		message.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: ip}}}
	}
	data, err := message.Pack()
	return data, q.Name.String(), err
}

func tunaNewWireFixture(t *testing.T, certificate tls.Certificate, beforeAnswer func(int, string)) *tunaWireFixture {
	t.Helper()
	f := &tunaWireFixture{t: t}
	for group := 0; group < 2; group++ {
		group := group
		listener, err := stdnet.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		f.addresses[group][0] = listener.Addr().String()
		t.Cleanup(func() { listener.Close() })
		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				go func() {
					defer conn.Close()
					conn.SetDeadline(time.Now().Add(5 * time.Second))
					var size [2]byte
					if _, err := io.ReadFull(conn, size[:]); err != nil {
						return
					}
					data := make([]byte, binary.BigEndian.Uint16(size[:]))
					if _, err := io.ReadFull(conn, data); err != nil {
						return
					}
					answer, name, err := tunaWireAnswer(data, [4]byte{127, 0, 0, 1})
					if err != nil || name != "same-doh.invalid." {
						t.Errorf("bootstrap resolved payload instead of endpoint: %s %v", name, err)
						return
					}
					f.bootstrap[group].Add(1)
					binary.BigEndian.PutUint16(size[:], uint16(len(answer)))
					conn.Write(append(size[:], answer...))
				}()
			}
		}()
		server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.ProtoMajor != 2 || r.TLS == nil || r.TLS.ServerName != "same-doh.invalid" || r.Host != "same-doh.invalid" || r.URL.Path != "/dns-query" || r.Method != "POST" {
				t.Error("HTTP/2 URL/SNI/Host identity changed")
				http.Error(w, "bad identity", 400)
				return
			}
			data, err := io.ReadAll(io.LimitReader(r.Body, 65536))
			if err != nil {
				t.Error(err)
				return
			}
			ip := [4]byte{192, 0, 2, 42}
			if group == 1 {
				ip[3] = 99
			}
			answer, name, err := tunaWireAnswer(data, ip)
			if err != nil {
				t.Error(err)
				return
			}
			f.requests[group].Add(1)
			if beforeAnswer != nil {
				beforeAnswer(group, name)
			}
			w.Header().Set("Content-Type", "application/dns-message")
			w.Write(answer)
		}))
		server.EnableHTTP2 = true
		server.TLS = &tls.Config{Certificates: []tls.Certificate{certificate}}
		server.StartTLS()
		f.addresses[group][1] = server.Listener.Addr().String()
		t.Cleanup(server.Close)
	}
	t.Cleanup(func() {
		f.mu.Lock()
		defer f.mu.Unlock()
		for _, conn := range f.connections {
			conn.Close()
		}
	})
	return f
}

func (f *tunaWireFixture) pool(t *testing.T, ctx context.Context, group string) *tunaDNSServer {
	t.Helper()
	spec, _ := json.Marshal(map[string]string{"endpoint": "https://same-doh.invalid/dns-query", "bootstrap": "tcp://192.0.2.90:53"})
	encoded, _ := json.Marshal(tunaPoolSpec{Group: group, Mode: "auto", TimeoutMs: 5000, Addresses: []string{"tuna-doh://" + base64.RawURLEncoding.EncodeToString(spec)}})
	server, err := newTunaDNSServer(ctx, "tuna-dns://"+base64.RawURLEncoding.EncodeToString(encoded), f, false, false, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	return server.(*tunaDNSServer)
}
