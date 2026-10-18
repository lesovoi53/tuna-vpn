package local

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

func tunaSystemFixture(t *testing.T, answer func(*mDNS.Msg)) *tunaSystemBootstrap {
	t.Helper()
	t.Setenv("TUNA_SYSTEM_DNS_SOCKET", "tuna-dns-test-session")
	transport, err := newTunaSystemBootstrap(context.Background(), "bootstrap")
	if err != nil {
		t.Fatal(err)
	}
	s := transport.(*tunaSystemBootstrap)
	t.Cleanup(func() { s.Close() })
	s.dial = func(context.Context, string) (net.Conn, error) {
		client, server := net.Pipe()
		go func() {
			defer server.Close()
			server.SetDeadline(time.Now().Add(time.Second))
			var header [4]byte
			if _, err := io.ReadFull(server, header[:]); err != nil {
				return
			}
			if budget := binary.BigEndian.Uint16(header[:2]); budget < 1 || budget > 5000 {
				t.Errorf("invalid bridge timeout %d", budget)
				return
			}
			data := make([]byte, binary.BigEndian.Uint16(header[2:]))
			if _, err := io.ReadFull(server, data); err != nil {
				return
			}
			var query mDNS.Msg
			if err := query.Unpack(data); err != nil {
				t.Error(err)
				return
			}
			response := new(mDNS.Msg).SetReply(&query)
			answer(response)
			data, err := response.Pack()
			if err != nil {
				t.Error(err)
				return
			}
			binary.BigEndian.PutUint16(header[:2], uint16(len(data)))
			server.Write(append(header[:2], data...))
		}()
		return client, nil
	}
	return s
}

func TestTunaSystemBootstrapWireAndNegatives(t *testing.T) {
	for _, scenario := range []string{"answer", "nxdomain", "nodata", "truncated", "id", "question"} {
		t.Run(scenario, func(t *testing.T) {
			s := tunaSystemFixture(t, func(m *mDNS.Msg) {
				switch scenario {
				case "answer":
					m.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: m.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: net.IP{192, 0, 2, 42}}}
				case "nxdomain":
					m.Rcode = mDNS.RcodeNameError
				case "truncated":
					m.Truncated = true
				case "id":
					m.Id++
				case "question":
					m.Question = nil
				}
			})
			response, err := s.Exchange(context.Background(), new(mDNS.Msg).SetQuestion("bootstrap.invalid.", mDNS.TypeA))
			if scenario == "truncated" || scenario == "id" || scenario == "question" {
				if err == nil {
					t.Fatal("malformed reply accepted")
				}
				return
			}
			if err != nil || response == nil {
				t.Fatalf("wire response: %v %v", response, err)
			}
			if scenario == "nxdomain" && response.Rcode != mDNS.RcodeNameError {
				t.Fatal("negative changed")
			}
			if scenario == "answer" && (len(response.Answer) != 1 || response.Answer[0].(*mDNS.A).A.String() != "192.0.2.42") {
				t.Fatal("wrong answer")
			}
		})
	}
}

func TestTunaSystemBootstrapCancellationAndClose(t *testing.T) {
	for _, closeTransport := range []bool{false, true} {
		s := tunaSystemFixture(t, func(*mDNS.Msg) {})
		ctx, cancel := context.WithCancel(context.Background())
		ready := make(chan struct{})
		s.dial = func(ctx context.Context, _ string) (net.Conn, error) {
			close(ready)
			<-ctx.Done()
			return nil, ctx.Err()
		}
		done := make(chan error, 1)
		go s.ExchangeAsync(ctx, new(mDNS.Msg).SetQuestion("cancel.invalid.", mDNS.TypeAAAA), func(_ *mDNS.Msg, err error) { done <- err })
		select {
		case <-ready:
		case <-time.After(time.Second):
			t.Fatal("did not dial")
		}
		if closeTransport {
			s.Close()
		} else {
			cancel()
		}
		select {
		case err := <-done:
			if !errors.Is(err, context.Canceled) {
				t.Fatal(err)
			}
		case <-time.After(time.Second):
			t.Fatal("cancellation stuck")
		}
		cancel()
	}
}

func TestTunaSystemBootstrapHookIsScopedAndFailsClosed(t *testing.T) {
	t.Setenv("TUNA_BOOTSTRAP_DNS", "system")
	for _, tag := range []string{"bootstrap", "remote-bootstrap", "direct-bootstrap"} {
		if !tunaSystemBootstrapEnabled(tag) {
			t.Fatal(tag)
		}
	}
	for _, tag := range []string{"local", "remote", "bootstrap-custom"} {
		if tunaSystemBootstrapEnabled(tag) {
			t.Fatal(tag)
		}
	}
	t.Setenv("TUNA_SYSTEM_DNS_SOCKET", "")
	if _, err := NewTransport(context.Background(), log.NewNOPFactory().Logger(), "bootstrap", option.LocalDNSServerOptions{}); err == nil {
		t.Fatal("missing bridge accepted")
	}
	t.Setenv("TUNA_SYSTEM_DNS_SOCKET", "tuna-dns-test-session")
	transport, err := NewTransport(context.Background(), log.NewNOPFactory().Logger(), "bootstrap", option.LocalDNSServerOptions{})
	if err != nil {
		t.Fatal(err)
	}
	defer transport.Close()
	if _, ok := transport.(*tunaSystemBootstrap); !ok {
		t.Fatal("bootstrap used libc or resolv.conf")
	}
	t.Setenv("TUNA_BOOTSTRAP_DNS", "tcp://192.0.2.1:53")
	if tunaSystemBootstrapEnabled("bootstrap") {
		t.Fatal("manual bootstrap redirected")
	}
}
