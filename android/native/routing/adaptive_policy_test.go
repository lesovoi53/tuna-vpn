package main

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	fdns "github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/infra/conf/serial"
	"golang.org/x/net/dns/dnsmessage"
)

func TestTunaAdaptivePolicyValidation(t *testing.T) {
	remote := tunaResolver{Transport: "TCP", Endpoint: "192.0.2.7:53"}
	for _, invalid := range []tunaSplitDNS{
		{RemoteServers: []tunaResolver{}, Domestic: remote},
		{RemoteServers: make([]tunaResolver, 9), Domestic: remote},
		{RemoteServers: []tunaResolver{remote, remote}, Domestic: remote},
		{Remote: remote, Domestic: remote, TimeoutMs: 999},
		{Remote: remote, Domestic: remote, TimeoutMs: 10001},
		{Remote: remote, Domestic: remote, RemoteSelectionMode: "unknown"},
	} {
		c := map[string]any{}
		if applySplitDNS(c, tunaPolicy{Mode: "ALL_VPN", DNS: &invalid}) == nil {
			t.Fatal("invalid pool accepted")
		}
	}
	dns := tunaSplitDNS{RemoteServers: []tunaResolver{remote, {Transport: "DOH", Endpoint: "https://dns.example/dns-query", BootstrapIP: "192.0.2.10"}}, Domestic: remote, RemoteSelectionMode: "auto", DomesticSelectionMode: "priority", TimeoutMs: 5000}
	c := map[string]any{}
	if err := applySplitDNS(c, tunaPolicy{Mode: "ALL_VPN", DNS: &dns}); err != nil {
		t.Fatal(err)
	}
	server := c["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any)
	encoded := strings.TrimPrefix(server["address"].(string), "tuna-dns://")
	data, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	var spec map[string]any
	json.Unmarshal(data, &spec)
	if spec["group"] != "remote" || len(spec["addresses"].([]any)) != 2 || server["timeoutMs"] != 5000 {
		t.Fatal("pool configuration lost")
	}
	legacy := map[string]any{}
	dns = tunaSplitDNS{Remote: remote, Domestic: remote}
	if err := applySplitDNS(legacy, tunaPolicy{Mode: "ALL_VPN", DNS: &dns}); err != nil {
		t.Fatal(err)
	}
	legacyServer := legacy["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any)
	legacySpec := decodeTunaPolicyPool(t, legacyServer["address"].(string))
	if legacySpec.Mode != "legacy" || legacySpec.TimeoutMs != 0 || len(legacySpec.Addresses) != 1 || legacySpec.Addresses[0] != "tcp://192.0.2.7:53" {
		t.Fatal("legacy singleton semantics changed")
	}
	if _, overridden := legacyServer["timeoutMs"]; overridden {
		t.Fatal("legacy client timeout overridden")
	}
}

func decodeTunaPolicyPool(t *testing.T, address string) struct {
	Mode      string
	TimeoutMs int
	Addresses []string
} {
	t.Helper()
	var spec struct {
		Mode      string
		TimeoutMs int
		Addresses []string
	}
	data, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(address, "tuna-dns://"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &spec); err != nil {
		t.Fatal(err)
	}
	return spec
}

// Run the actual legacy policy -> core -> TCP resolver path. That fixture also
// checks remote/direct isolation, static hosts and BLOCK without external DNS.
func TestTunaLegacyPolicyRealTelemetry(t *testing.T) {
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	original := os.Stdout
	os.Stdout = write
	output := make(chan string, 1)
	go func() { data, _ := io.ReadAll(read); output <- string(data) }()
	defer func() { os.Stdout = original; write.Close(); read.Close() }()
	TestTunaSplitDNSRealTraffic(t)
	os.Stdout = original
	write.Close()
	var status []string
	for _, line := range strings.Split(<-output, "\n") {
		if strings.HasPrefix(line, "TUNA_DNS_STATUS ") {
			status = append(status, strings.TrimSpace(line))
		}
	}
	if strings.Join(status, "\n") != "TUNA_DNS_STATUS domestic 1 selected\nTUNA_DNS_STATUS remote 1 selected" {
		t.Fatalf("legacy answer telemetry: %q", status)
	}
}

func TestTunaDoHBootstrapPinDoesNotCrossPools(t *testing.T) {
	doh := tunaResolver{Transport: "DOH", Endpoint: "https://same-doh.invalid/dns-query"}
	pinned := doh
	pinned.BootstrapIP = "192.0.2.80"
	policy := tunaPolicy{Mode: "SELECTED_VPN", DNS: &tunaSplitDNS{Remote: pinned, Domestic: doh}}
	c := map[string]any{}
	if err := applySplitDNS(c, policy); err != nil {
		t.Fatal(err)
	}
	address := c["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any)["address"].(string)
	if !strings.HasPrefix(decodeTunaPolicyPool(t, address).Addresses[0], "tuna-doh://") {
		t.Fatal("automatic domestic endpoint borrowed remote bootstrap pin")
	}
	policy.DNS.Hosts = map[string]string{"same-doh.invalid": "192.0.2.80"}
	if err := applySplitDNS(c, policy); err != nil {
		t.Fatal(err)
	}
	address = c["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any)["address"].(string)
	if decodeTunaPolicyPool(t, address).Addresses[0] != doh.Endpoint {
		t.Fatal("explicit global static host ignored")
	}
}

// The bootstrap address is a documentation-only IP and cannot answer directly.
// Only our local SOCKS fixture answers it. A failed DoH TLS connection must use
// the next remote pool member within the total deadline, never domestic DNS.
func TestTunaDoHBootstrapViaProxyAndPoolFallback(t *testing.T) {
	t.Setenv("TUNA_BOOTSTRAP_DNS", "tcp://192.0.2.90:53")
	proxy := startTestChannel(t, "127.0.0.1:0", 73)
	reserve, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserve.Addr().String()
	reserve.Close()
	raw, err := buildSocks5XrayConfig(proxy.listener.Addr().String(), "channel-73", "pass-73", address, "", []string{"192.0.2.7"}, false, "u", "p", "", "", false)
	if err != nil {
		t.Fatal(err)
	}
	policy := tunaPolicy{Mode: "SELECTED_VPN", Rules: []tunaRule{{"full:payload.invalid", "VPN", true}}, DNS: &tunaSplitDNS{
		RemoteServers:       []tunaResolver{{Transport: "DOH", Endpoint: "https://private-doh.invalid/dns-query"}, {Transport: "TCP", Endpoint: "192.0.2.7:53"}},
		Domestic:            tunaResolver{Transport: "TCP", Endpoint: "127.0.0.1:1"},
		RemoteSelectionMode: "priority", TimeoutMs: 1000, QueryStrategy: "UseIPv4",
	}}
	data, _ := json.Marshal(policy)
	path := filepath.Join(t.TempDir(), "routing.json")
	os.WriteFile(path, data, 0600)
	raw, err = applyTunaPolicy(raw, path)
	if err != nil {
		t.Fatal(err)
	}
	raw, err = applyTunaSocksResolution(raw, true)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := serial.LoadJSONConfig(strings.NewReader(string(raw)))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := core.New(parsed)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	ips, _, err := instance.GetFeature(fdns.ClientType()).(fdns.Client).LookupIP("payload.invalid", fdns.IPOption{IPv4Enable: true})
	if err != nil || len(ips) != 1 || ips[0].String() != "192.0.2.42" {
		t.Fatalf("remote fallback: %v %v", ips, err)
	}
	if proxy.dnsRequests.Load() < 2 {
		t.Fatal("DoH bootstrap did not use the VPN proxy")
	}
	if time.Since(start) > 1500*time.Millisecond {
		t.Fatal("bootstrap multiplied the pool deadline")
	}
}

func TestTunaAdaptivePolicyRealTrafficIsolation(t *testing.T) {
	proxy := startTestChannel(t, "127.0.0.1:0", 73)
	direct, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer direct.Close()
	var directQueries atomic.Int32
	go func() {
		for {
			conn, err := direct.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(time.Second))
				var size [2]byte
				if _, err := io.ReadFull(conn, size[:]); err != nil {
					return
				}
				bytes := make([]byte, binary.BigEndian.Uint16(size[:]))
				if _, err := io.ReadFull(conn, bytes); err != nil {
					return
				}
				var message dnsmessage.Message
				if message.Unpack(bytes) != nil {
					return
				}
				directQueries.Add(1)
				message.Response = true
				message.RecursionAvailable = true
				for _, q := range message.Questions {
					if q.Type == dnsmessage.TypeA {
						message.Answers = append(message.Answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, 99}}})
					}
				}
				bytes, _ = message.Pack()
				binary.BigEndian.PutUint16(size[:], uint16(len(bytes)))
				conn.Write(append(size[:], bytes...))
			}()
		}
	}()
	reserve, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserve.Addr().String()
	reserve.Close()
	raw, err := buildSocks5XrayConfig(proxy.listener.Addr().String(), "channel-73", "pass-73", address, "", []string{"192.0.2.7"}, false, "u", "p", "", "", false)
	if err != nil {
		t.Fatal(err)
	}
	policy := tunaPolicy{Mode: "SELECTED_DIRECT", Rules: []tunaRule{{"full:direct.invalid", "DIRECT", true}, {"full:blocked.invalid", "BLOCK", true}}, DNS: &tunaSplitDNS{
		RemoteServers:       []tunaResolver{{Transport: "TCP", Endpoint: "192.0.2.7:53"}, {Transport: "TCP", Endpoint: "192.0.2.8:53"}},
		DomesticServers:     []tunaResolver{{Transport: "TCP", Endpoint: "127.0.0.1:1"}, {Transport: "TCP", Endpoint: direct.Addr().String()}},
		RemoteSelectionMode: "auto", DomesticSelectionMode: "priority", TimeoutMs: 1000, QueryStrategy: "UseIPv4",
	}}
	data, _ := json.Marshal(policy)
	path := filepath.Join(t.TempDir(), "routing.json")
	os.WriteFile(path, data, 0600)
	raw, err = applyTunaPolicy(raw, path)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := serial.LoadJSONConfig(strings.NewReader(string(raw)))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := core.New(parsed)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	dns := instance.GetFeature(fdns.ClientType()).(fdns.Client)
	ips, _, err := dns.LookupIP("direct.invalid", fdns.IPOption{IPv4Enable: true})
	if err != nil || len(ips) != 1 || ips[0].String() != "192.0.2.99" {
		t.Fatalf("direct failover: %v %v", ips, err)
	}
	if proxy.dnsRequests.Load() != 0 || directQueries.Load() != 1 {
		t.Fatal("direct fallback entered VPN")
	}
	ips, _, err = dns.LookupIP("remote.invalid", fdns.IPOption{IPv4Enable: true})
	if err != nil || len(ips) != 1 || ips[0].String() != "192.0.2.42" {
		t.Fatalf("remote pool: %v %v", ips, err)
	}
	if proxy.dnsRequests.Load() != 1 || directQueries.Load() != 1 {
		t.Fatal("remote pool fanned out or leaked direct")
	}
	if _, _, err = dns.LookupIP("blocked.invalid", fdns.IPOption{IPv4Enable: true}); err == nil {
		t.Fatal("blocked query succeeded")
	}
	if proxy.dnsRequests.Load() != 1 || directQueries.Load() != 1 {
		t.Fatal("blocked query escaped")
	}
}
