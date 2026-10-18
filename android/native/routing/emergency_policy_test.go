package main

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func TestTunaEmergencyPolicyRoundTrip(t *testing.T) {
	var policy tunaPolicy
	if err := json.Unmarshal([]byte(`{"mode":"ALL_VPN","splitDns":{"remote":{"transport":"TCP","endpoint":"192.0.2.1"},"domestic":{"transport":"TCP","endpoint":"192.0.2.2"},"remoteEmergency":{"mode":"system"},"bootstrap":{"mode":"system"}}}`), &policy); err != nil {
		t.Fatal(err)
	}
	config := map[string]any{}
	if err := applySplitDNS(config, policy); err != nil {
		t.Fatal(err)
	}
	address := config["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any)["address"].(string)
	data, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(address, "tuna-dns://"))
	if err != nil {
		t.Fatal(err)
	}
	var spec map[string]any
	json.Unmarshal(data, &spec)
	if spec["emergency"] != "tuna-system://android" || spec["mode"] == "legacy" {
		t.Fatalf("terminal system emergency lost: %s", data)
	}
}
