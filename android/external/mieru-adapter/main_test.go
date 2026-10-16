package main

import (
	pb "github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"net/url"
	"testing"
)

const example = "mierus://test:test-password@192.0.2.1/?profile=Test%20Mieru&port=20715-20735&protocol=TCP&multiplexing=MULTIPLEXING_HIGH"

func TestLocalAuthenticationDefaults(t *testing.T) {
	input := launch{URI: example, Listen: "127.0.0.1:2081"}
	if _, err := configuration(input); err == nil {
		t.Fatal("accepted missing local credentials")
	}
	input.Username = "local-user"
	input.Password = "local-pass"
	cfg, err := configuration(input)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Socks5Authentication) != 1 || cfg.Socks5Authentication[0].GetUser() != input.Username {
		t.Fatal("local auth missing")
	}
	input.AllowNoAuth = true
	cfg, err = configuration(input)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Socks5Authentication) != 0 {
		t.Fatal("explicit opt out ignored")
	}
}

func TestAllSupportedLowEntropyEnums(t *testing.T) {
	for mode, modeValue := range pb.LowEntropyMode_value {
		for rotation, rotationValue := range pb.LowEntropyMaskRotation_value {
			cfg, err := configuration(launch{URI: example + "&low-entropy-mode=" + mode + "&low-entropy-mask-rotation=" + rotation, Listen: "127.0.0.1:2081", Username: "local-user", Password: "local-pass"})
			if err != nil {
				t.Fatalf("%s/%s: %v", mode, rotation, err)
			}
			p := cfg.Profiles[0]
			if int32(p.GetTrafficPattern().GetLowEntropy().GetMode()) != modeValue || int32(p.GetTrafficPattern().GetLowEntropy().GetMaskRotation()) != rotationValue {
				t.Fatal("enum lost")
			}
			if p.Servers[0].PortBindings[0].GetPortRange() != "20715-20735" || p.GetMultiplexing().GetLevel() != pb.MultiplexingLevel_MULTIPLEXING_HIGH {
				t.Fatal("profile options lost")
			}
		}
	}
}
func TestPatternAndExplicitOptionsAgree(t *testing.T) {
	pattern := "EAEaBAgBEA8iCAgBEAEYBiAIKgUIQBCAATIECAEQBw=="
	uri := example + "&traffic-pattern=" + url.QueryEscape(pattern) + "&low-entropy-mode=LOW_ENTROPY_MODE_32&low-entropy-mask-rotation=LOW_ENTROPY_MASK_ROTATE_RIGHT_7"
	cfg, err := configuration(launch{URI: uri, Listen: "127.0.0.1:2081", Username: "local-user", Password: "local-pass"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Profiles[0].GetTrafficPattern().GetLowEntropy().GetMaskRotation() != pb.LowEntropyMaskRotation_LOW_ENTROPY_MASK_ROTATE_RIGHT_7 {
		t.Fatal("rotation lost")
	}
}
func TestRejectsUnknownOptionsAndPublicListener(t *testing.T) {
	for _, suffix := range []string{"&low-entropy-mode=UNKNOWN", "&low-entropy-mask-rotation=UNKNOWN", "&multiplexing=INVALID", "&unknown=x", "&profile=duplicate", "&traffic-pattern=invalid"} {
		if _, err := configuration(launch{URI: example + suffix, Listen: "127.0.0.1:2081", Username: "local-user", Password: "local-pass"}); err == nil {
			t.Fatalf("accepted %s", suffix)
		}
	}
	if _, err := configuration(launch{URI: example, Listen: "0.0.0.0:2081", Username: "local-user", Password: "local-pass"}); err == nil {
		t.Fatal("public listener accepted")
	}
}

func TestRejectsContradictoryTrafficPattern(t *testing.T) {
	pattern := "EAEaBAgBEA8iCAgBEAEYBiAIKgUIQBCAATIECAEQBw=="
	for _, tc := range []struct{ option, reason string }{
		{"low-entropy-mode=LOW_ENTROPY_MODE_48", "conflicting low entropy mode"},
		{"low-entropy-mask-rotation=LOW_ENTROPY_MASK_NO_ROTATION", "conflicting mask rotation"},
	} {
		uri := example + "&traffic-pattern=" + url.QueryEscape(pattern) + "&" + tc.option
		_, err := configuration(launch{URI: uri, Listen: "127.0.0.1:2081", Username: "local-user", Password: "local-pass"})
		if err == nil || err.Error() != tc.reason {
			t.Fatalf("expected safe conflict diagnostic %q, got %v", tc.reason, err)
		}
	}
}
