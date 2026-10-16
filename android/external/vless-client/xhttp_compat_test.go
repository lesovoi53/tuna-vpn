package main

import (
	"encoding/json"
	"net/url"
	"testing"

	"github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/xray-core/transport/internet/splithttp"
)

func TestXHTTPModernAndLegacySessionOptions(t *testing.T) {
	for _, extra := range []string{
		`{"sessionIDPlacement":"cookie","sessionIDKey":"sess","sessionIDTable":"Base62","sessionIDLength":"16-32"}`,
		`{"sessionPlacement":"cookie","sessionKey":"sess","sessionIDTable":"Base62","sessionIDLength":"16-32"}`,
		`{"sessionPlacement":"header","sessionKey":"old","sessionIDPlacement":"cookie","sessionIDKey":"sess","sessionIDTable":"Base62","sessionIDLength":"16-32"}`,
	} {
		cfg, err := parseProxyLink("vless://00000000-0000-4000-8000-000000000001@example.com:443?type=xhttp&mode=stream-up&extra=" + url.QueryEscape(extra))
		if err != nil {
			t.Fatal(err)
		}
		outbound := buildOutbound(cfg, "vpn", 0)
		settings := outbound["streamSettings"].(map[string]any)["xhttpSettings"]
		raw, err := json.Marshal(settings)
		if err != nil {
			t.Fatal(err)
		}
		var options conf.SplitHTTPConfig
		if err := json.Unmarshal(raw, &options); err != nil {
			t.Fatal(err)
		}
		built, err := options.Build()
		if err != nil {
			t.Fatal(err)
		}
		actual := built.(*splithttp.Config)
		if actual.SessionIDPlacement != "cookie" || actual.SessionIDKey != "sess" || len(actual.SessionIDTable) != 62 || actual.SessionIDLength.From != 16 || actual.SessionIDLength.To != 32 {
			t.Fatal("XHTTP session options were not preserved in compiled core configuration")
		}
	}
}
