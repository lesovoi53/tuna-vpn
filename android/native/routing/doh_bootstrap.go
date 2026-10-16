package dns

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"github.com/xtls/xray-core/common/net"
	fdns "github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/features/routing"
	"golang.org/x/net/http2"
)

// Bootstrap questions are endpoint names only. Both bootstrap TCP and DoH
// retain the caller's DNS routing tag; neither uses the OS/direct resolver.
func newTunaDoHServer(encoded string, dispatcher routing.Dispatcher, disableCache, serveStale bool, ttl uint32, clientIP net.IP) (Server, error) {
	data, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(encoded, "tuna-doh://"))
	if err != nil || len(data) > 8192 {
		return nil, fmt.Errorf("invalid DoH bootstrap encoding")
	}
	var spec struct {
		Endpoint  string `json:"endpoint"`
		Bootstrap string `json:"bootstrap"`
	}
	if json.Unmarshal(data, &spec) != nil {
		return nil, fmt.Errorf("invalid DoH bootstrap configuration")
	}
	endpoint, err := url.Parse(spec.Endpoint)
	if err != nil || endpoint.Scheme != "https" || endpoint.Hostname() == "" || endpoint.User != nil || endpoint.Fragment != "" {
		return nil, fmt.Errorf("invalid DoH endpoint")
	}
	bootstrapURL, err := url.Parse(spec.Bootstrap)
	if err != nil || bootstrapURL.Scheme != "tcp" || net.ParseIP(bootstrapURL.Hostname()) == nil || bootstrapURL.User != nil || bootstrapURL.Path != "" || bootstrapURL.RawQuery != "" || bootstrapURL.Fragment != "" {
		return nil, fmt.Errorf("DoH bootstrap requires tcp://literal-IP:port")
	}
	bootstrap, err := NewTCPNameServer(bootstrapURL, dispatcher, false, false, 0, nil)
	if err != nil {
		return nil, err
	}
	server := NewDoHNameServer(endpoint, dispatcher, false, disableCache, serveStale, ttl, clientIP)
	transport := server.httpClient.Transport.(*http2.Transport)
	transport.DialTLSContext = tunaDoHBootstrapDial(bootstrap, endpoint.Hostname(), transport.DialTLSContext)
	return server, nil
}

func tunaDoHBootstrapDial(bootstrap Server, hostname string, dial func(context.Context, string, string, *tls.Config) (net.Conn, error)) func(context.Context, string, string, *tls.Config) (net.Conn, error) {
	return func(ctx context.Context, network, address string, config *tls.Config) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil || !strings.EqualFold(host, hostname) {
			return nil, fmt.Errorf("unexpected DoH bootstrap destination")
		}
		ips, _, err := bootstrap.QueryIP(ctx, hostname, fdns.IPOption{IPv4Enable: true, IPv6Enable: true})
		if err != nil {
			return nil, err
		}
		for _, ip := range ips {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			var conn net.Conn
			// The original DoH closure keeps the URL's TLS ServerName and Host.
			conn, err = dial(ctx, network, net.JoinHostPort(ip.String(), port), config)
			if err == nil {
				return conn, nil
			}
		}
		if err == nil {
			err = fdns.ErrEmptyResponse
		}
		return nil, err
	}
}
