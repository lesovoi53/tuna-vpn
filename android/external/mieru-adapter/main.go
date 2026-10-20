// SPDX-License-Identifier: GPL-3.0-or-later
// TUNA VPN adapter: upstream Mieru protocol, strict extended URI import.
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"

	"github.com/enfein/mieru/v3/pkg/appctl"
	pb "github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"github.com/enfein/mieru/v3/pkg/cli"
	"github.com/enfein/mieru/v3/pkg/version"
	"google.golang.org/protobuf/proto"
)

type launch struct {
	URI         string `json:"uri"`
	Listen      string `json:"listen"`
	Username    string `json:"username"`
	Password    string `json:"password"`
	AllowNoAuth bool   `json:"allowNoAuth"`
}

func configuration(input launch) (*pb.ClientConfig, error) {
	if !input.AllowNoAuth && (input.Username == "" || input.Password == "") {
		return nil, fmt.Errorf("local authentication required")
	}
	u, err := url.Parse(input.URI)
	if err != nil {
		return nil, fmt.Errorf("invalid URI")
	}
	q, err := url.ParseQuery(u.RawQuery)
	if err != nil {
		return nil, fmt.Errorf("invalid query")
	}
	allowed := map[string]bool{"profile": true, "port": true, "protocol": true, "multiplexing": true, "traffic-pattern": true, "mtu": true, "handshake-mode": true, "low-entropy-mode": true, "low-entropy-mask-rotation": true}
	for key, values := range q {
		if !allowed[key] || (key != "port" && key != "protocol" && len(values) != 1) {
			return nil, fmt.Errorf("unknown or duplicate option")
		}
	}
	if len(q["port"]) == 0 || len(q["port"]) != len(q["protocol"]) {
		return nil, fmt.Errorf("invalid port bindings")
	}
	for _, v := range q["protocol"] {
		if v != "TCP" && v != "UDP" {
			return nil, fmt.Errorf("invalid transport")
		}
	}
	if v := q.Get("multiplexing"); v != "" {
		if _, ok := pb.MultiplexingLevel_value[v]; !ok {
			return nil, fmt.Errorf("invalid multiplexing")
		}
	}
	if v := q.Get("handshake-mode"); v != "" {
		if _, ok := pb.HandshakeMode_value[v]; !ok {
			return nil, fmt.Errorf("invalid handshake mode")
		}
	}
	profile, err := appctl.URLToClientProfile(input.URI)
	if err != nil {
		return nil, fmt.Errorf("invalid Mieru profile")
	}
	if q.Has("low-entropy-mode") || q.Has("low-entropy-mask-rotation") {
		if profile.TrafficPattern == nil {
			profile.TrafficPattern = &pb.TrafficPattern{}
		}
		if profile.TrafficPattern.LowEntropy == nil {
			profile.TrafficPattern.LowEntropy = &pb.LowEntropyPattern{}
		}
		low := profile.TrafficPattern.LowEntropy
		if q.Has("low-entropy-mode") {
			n, ok := pb.LowEntropyMode_value[q.Get("low-entropy-mode")]
			if !ok {
				return nil, fmt.Errorf("invalid low entropy mode")
			}
			value := pb.LowEntropyMode(n)
			if low.Mode != nil && low.GetMode() != value {
				return nil, fmt.Errorf("conflicting low entropy mode")
			}
			low.Mode = &value
		}
		if q.Has("low-entropy-mask-rotation") {
			n, ok := pb.LowEntropyMaskRotation_value[q.Get("low-entropy-mask-rotation")]
			if !ok {
				return nil, fmt.Errorf("invalid mask rotation")
			}
			value := pb.LowEntropyMaskRotation(n)
			if low.MaskRotation != nil && low.GetMaskRotation() != value {
				return nil, fmt.Errorf("conflicting mask rotation")
			}
			low.MaskRotation = &value
		}
	}
	host, port, err := net.SplitHostPort(input.Listen)
	if err != nil || host != "127.0.0.1" {
		return nil, fmt.Errorf("loopback listener required")
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return nil, fmt.Errorf("invalid listen port")
	}
	cfg := &pb.ClientConfig{Profiles: []*pb.ClientProfile{profile}, ActiveProfile: profile.ProfileName, Socks5Port: proto.Int32(int32(n)), RpcPort: proto.Int32(0), LoggingLevel: pb.LoggingLevel_INFO.Enum(), AdvancedSettings: &pb.ClientAdvancedSettings{NoCheckUpdate: proto.Bool(true)}}
	if !input.AllowNoAuth {
		cfg.Socks5Authentication = []*pb.Auth{{User: proto.String(input.Username), Password: proto.String(input.Password)}}
	}
	if err := appctl.ValidateFullClientConfig(cfg); err != nil {
		return nil, fmt.Errorf("Mieru configuration validation failed")
	}
	return cfg, nil
}

func run() error {
	if len(os.Args) != 2 {
		return fmt.Errorf("expected launch file")
	}
	path := os.Args[1]
	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("cannot read launch file")
	}
	var input launch
	if json.Unmarshal(data, &input) != nil {
		return fmt.Errorf("invalid launch file")
	}
	cfg, err := configuration(input)
	if err != nil {
		return err
	}
	if err = appctl.SetTunaMemoryConfig(cfg); err != nil {
		return fmt.Errorf("cannot set runtime configuration")
	}
	// The upstream run command uses this presence marker to keep logging on stdout.
	// LoadClientConfig is supplied by the memory overlay and never reads this path.
	if err = os.Setenv(appctl.EnvMieruConfigJSONFile, "tuna-memory"); err != nil {
		return fmt.Errorf("cannot configure runtime environment")
	}
	appctl.RecordAppStartTime()
	appctl.SetAppType(appctl.CLIENT_APP)
	cli.RegisterClientCommands()
	os.Args = []string{os.Args[0], "run"}
	if cli.ParseAndExecute() != nil {
		return fmt.Errorf("native runtime failed")
	}
	return nil
}
func main() {
	if len(os.Args) == 2 && os.Args[1] == "--version" {
		fmt.Printf("Mieru %s (TUNA adapter)\n", version.AppVersion)
		return
	}
	if err := run(); err != nil {
		// run returns fixed diagnostic messages, never the URI or upstream errors
		// that could contain credentials. Preserve the reason for a rejected start.
		fmt.Fprintln(os.Stderr, "[MIERU] fatal:", err)
		os.Exit(1)
	}
}
