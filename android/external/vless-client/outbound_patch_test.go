package main

import (
	"encoding/json"
	"testing"
)

func TestOutboundPatchPreservesManagedConfig(t *testing.T) {
	raw := []byte(`{"inbounds":[{"port":1080}],"routing":{"domainStrategy":"AsIs"},"outbounds":[{"protocol":"vless","tag":"proxy","streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/test"}}}]}`)
	result, err := applyOutboundPatch(raw, map[string]any{"streamSettings": map[string]any{"xhttpSettings": map[string]any{"mode": "stream-up"}}})
	if err != nil {
		t.Fatal(err)
	}
	var obj map[string]any
	json.Unmarshal(result, &obj)
	if obj["routing"].(map[string]any)["domainStrategy"] != "AsIs" {
		t.Fatal("routing lost")
	}
	out := obj["outbounds"].([]any)[0].(map[string]any)
	settings := out["streamSettings"].(map[string]any)["xhttpSettings"].(map[string]any)
	if settings["path"] != "/test" || settings["mode"] != "stream-up" {
		t.Fatal("deep merge lost fields")
	}
	for _, patch := range []map[string]any{{"protocol": "freedom"}, {"streamSettings": map[string]any{"invented": true}}, {"tag": "direct"}, {"streamSettings": map[string]any{"sockopt": map[string]any{"dialerProxy": "direct"}}}} {
		if _, err := applyOutboundPatch(raw, patch); err == nil {
			t.Fatal("managed override allowed")
		}
	}
}
