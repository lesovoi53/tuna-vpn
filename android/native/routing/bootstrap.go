package main

import (
    "context"
    "fmt"
    "log"
    "net"
    "net/url"
    "os"
    "time"
)

// Bootstrap is deliberately separate from Xray's DNS dispatcher: resolving the
// VPN server through that same outbound creates a recursive connection dependency.
func configureBootstrapResolver(value string) error {
    if value == "" { return nil }
    endpoint, err := url.Parse(value)
    if err != nil || endpoint.Scheme != "tcp" || endpoint.User != nil || endpoint.Path != "" || endpoint.RawQuery != "" || endpoint.Fragment != "" || net.ParseIP(endpoint.Hostname()) == nil {
        return fmt.Errorf("bootstrap DNS requires tcp://literal-IP:port")
    }
    port := endpoint.Port()
    if port == "" { port = "53" }
    address := net.JoinHostPort(endpoint.Hostname(), port)
    parsed, err := net.ResolveTCPAddr("tcp", address)
    if err != nil || parsed.Port < 1 || parsed.Port > 65535 { return fmt.Errorf("invalid bootstrap DNS port") }
    net.DefaultResolver = &net.Resolver{
        PreferGo: true,
        Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
            return (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, "tcp", address)
        },
    }
    return nil
}

func init() {
    if err := configureBootstrapResolver(os.Getenv("TUNA_BOOTSTRAP_DNS")); err != nil { log.Fatal(err) }
}
