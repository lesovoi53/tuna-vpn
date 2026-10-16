package main

import (
	"encoding/json"
	"github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/xray-core/transport/internet/finalmask/udphop"
	"os"
	"testing"
)

// Consumes the actual Android generator fixture, rather than recreating its JSON.
func TestAndroidHoppingCompilesToNativeMask(t *testing.T) {
	path := "../../app/build/xray-test-configs/hy2-hopping.json"
	if _, err := os.Stat(path); err != nil {
		t.Skip("run Android generator tests first")
	}
	fc, err := loadFileConfig(path, true)
	if err != nil {
		t.Fatal(err)
	}
	proxy, err := parseProxyLink(fc.Link)
	if err != nil {
		t.Fatal(err)
	}
	outbound := buildOutbound(proxy, "proxy", 0)
	data, err := json.Marshal(outbound["streamSettings"])
	if err != nil {
		t.Fatal(err)
	}
	var stream conf.StreamConfig
	if err := json.Unmarshal(data, &stream); err != nil {
		t.Fatal(err)
	}
	compiled, err := stream.Build()
	if err != nil {
		t.Fatal(err)
	}
	if len(compiled.Udpmasks) != 2 {
		t.Fatalf("hopping + obfuscation not compiled: %d masks", len(compiled.Udpmasks))
	}
	found := false
	for _, mask := range compiled.Udpmasks {
		value, err := mask.GetInstance()
		if err != nil {
			t.Fatal(err)
		}
		if hop, ok := value.(*udphop.Config); ok {
			found = true
			if !hop.Remote || hop.IntervalMin != 5 || hop.IntervalMax != 10 || len(hop.RemotePorts) != 102 {
				t.Fatal("incorrect native hopping configuration")
			}
		}
	}
	if !found {
		t.Fatal("UDP hopping silently dropped")
	}
}
