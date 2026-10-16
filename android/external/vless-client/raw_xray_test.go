package main

import (
	"encoding/json"
	"flag"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
)

func rawXrayFixture(t *testing.T) map[string]any {
	t.Helper()
	var cfg map[string]any
	err := json.Unmarshal([]byte(`{"inbounds":[{"tag":"tuna-json-in","listen":"127.0.0.1","port":2081,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"test","pass":"test"}],"udp":true}}],"outbounds":[{"tag":"edge1","protocol":"freedom","settings":{}},{"tag":"edge2","protocol":"freedom","settings":{}},{"tag":"block","protocol":"blackhole","settings":{}}],"dns":{"servers":["1.1.1.1"],"tag":"resolver"},"routing":{"domainStrategy":"AsIs","balancers":[{"tag":"balance","selector":["edge"],"strategy":{"type":"random"}}],"rules":[{"type":"field","domain":["domain:example.test"],"outboundTag":"block"},{"type":"field","network":"tcp,udp","balancerTag":"balance"}]},"policy":{"system":{"statsOutboundUplink":true}},"stats":{},"observatory":{"subjectSelector":["edge"],"probeURL":"https://example.org/","probeInterval":"1m"}}`), &cfg)
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

func TestRawXrayPreservesNativePolicyAndBuildsCore(t *testing.T) {
	source := rawXrayFixture(t)
	raw, err := buildRawXrayConfig(source, "127.0.0.1:2081", "test", "test")
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(raw, &result); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"outbounds", "routing", "dns", "policy", "stats", "observatory"} {
		if !reflect.DeepEqual(source[key], result[key]) {
			t.Fatalf("changed native %s", key)
		}
	}
	config, err := serial.LoadJSONConfig(strings.NewReader(string(raw)))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := core.New(config)
	if err != nil {
		t.Fatal(err)
	}
	instance.Close()
}

func TestRawXrayRejectsUnsafeWrapper(t *testing.T) {
	for _, test := range []struct {
		name   string
		mutate func(map[string]any)
	}{
		{"api", func(c map[string]any) { c["api"] = map[string]any{} }},
		{"file", func(c map[string]any) {
			c["outbounds"].([]any)[0].(map[string]any)["certificateFile"] = "/private/read"
		}},
		{"external", func(c map[string]any) { c["inbounds"].([]any)[0].(map[string]any)["listen"] = "0.0.0.0" }},
		{"auth", func(c map[string]any) {
			c["inbounds"].([]any)[0].(map[string]any)["settings"].(map[string]any)["auth"] = "noauth"
		}},
		{"extra listener", func(c map[string]any) { c["inbounds"] = append(c["inbounds"].([]any), map[string]any{}) }},
	} {
		t.Run(test.name, func(t *testing.T) {
			cfg := rawXrayFixture(t)
			test.mutate(cfg)
			if _, err := buildRawXrayConfig(cfg, "127.0.0.1:2081", "test", "test"); err == nil {
				t.Fatal("unsafe configuration accepted")
			}
		})
	}
}

func TestAndroidRawXrayFixtures(t *testing.T) {
	directory := os.Getenv("TUNA_RAW_XRAY_FIXTURES")
	if directory == "" {
		t.Skip("Set TUNA_RAW_XRAY_FIXTURES to app/build/raw-xray-test-configs")
	}
	files, err := filepath.Glob(filepath.Join(directory, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatal("Missing Android raw fixtures", err)
	}
	for _, path := range files {
		t.Run(filepath.Base(path), func(t *testing.T) {
			fc, err := loadFileConfig(path, true)
			if err != nil {
				t.Fatal(err)
			}
			raw, err := buildRawXrayConfig(fc.RawXray, fc.Listen, fc.ProxyUser, fc.ProxyPass)
			if err != nil {
				t.Fatal(err)
			}
			config, err := serial.LoadJSONConfig(strings.NewReader(string(raw)))
			if err != nil {
				t.Fatal(err)
			}
			instance, err := core.New(config)
			if err != nil {
				t.Fatal(err)
			}
			instance.Close()
		})
	}
}

// Exercise the real -config loader and routing-overlay branch. A missing policy
// file must not matter to a full profile, whose native routing is authoritative.
func TestRawXrayWrapperBypassesGeneratedPolicy(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "wrapper.json")
	data, err := json.Marshal(map[string]any{"raw_xray": rawXrayFixture(t), "listen": "127.0.0.1:2081", "proxy_user": "test", "proxy_pass": "test"})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(exe, "-test.run=^TestRawXraySubprocess$")
	command.Env = append(os.Environ(), "TUNA_RAW_XRAY_HELPER="+path, "TUNA_ROUTING_FILE="+filepath.Join(directory, "absent.json"), "TUNA_RESOLVE_SOCKS_DESTINATION=1")
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("wrapper rejected full config: %s", output)
	}
	var dumped map[string]any
	for _, line := range strings.Split(string(output), "\n") {
		if strings.HasPrefix(line, "{") {
			if err := json.Unmarshal([]byte(line), &dumped); err != nil {
				t.Fatal(err)
			}
		}
	}
	if dumped == nil || !reflect.DeepEqual(rawXrayFixture(t)["routing"], dumped["routing"]) {
		t.Fatal("native routing was replaced")
	}
}

func TestRawXraySubprocess(t *testing.T) {
	path := os.Getenv("TUNA_RAW_XRAY_HELPER")
	if path == "" {
		t.Skip("helper process")
	}
	os.Args = []string{os.Args[0], "-config", path, "-dump-config"}
	flag.CommandLine = flag.NewFlagSet("xray", flag.ExitOnError)
	main()
}
