package wireguard

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/wireguard-go/device"
)

func TestTunaKeepaliveRangeReachesPinnedWireGuard(t *testing.T) {
	for _, input := range []string{`0`, `25`, `65535`, `"20-35"`, `"0-25"`, `"65534-65535"`, `"25-25"`} {
		t.Run(input, func(t *testing.T) {
			var parsed option.WireGuardPeer
			if err := json.Unmarshal([]byte(`{"persistent_keepalive_interval":`+input+`}`), &parsed); err != nil {
				t.Fatal(err)
			}
			peer := peerConfig{keepalive: parsed.PersistentKeepaliveInterval}
			ipc := peer.GenerateIpcLines()
			if parsed.PersistentKeepaliveInterval.IsZero() {
				if strings.Contains(ipc, "persistent_keepalive_interval=") {
					t.Fatal("disabled keepalive emitted")
				}
				return
			}
			line := "persistent_keepalive_interval=" + parsed.PersistentKeepaliveInterval.String()
			if !strings.Contains(ipc, "\n"+line) {
				t.Fatal("keepalive was lost before UAPI")
			}
			var native device.UintRange
			if err := native.FromString(strings.TrimPrefix(line, "persistent_keepalive_interval=")); err != nil {
				t.Fatal(err)
			}
			if native.ToString() != parsed.PersistentKeepaliveInterval.String() {
				t.Fatal("native range changed")
			}
			serialized, err := json.Marshal(parsed)
			if err != nil {
				t.Fatal(err)
			}
			var again option.WireGuardPeer
			if err := json.Unmarshal(serialized, &again); err != nil {
				t.Fatal(err)
			}
			if again.PersistentKeepaliveInterval != parsed.PersistentKeepaliveInterval {
				t.Fatal("JSON round trip changed interval")
			}
		})
	}
}

func TestTunaKeepaliveRejectsInvalidRangesWithoutTruncation(t *testing.T) {
	for _, input := range []string{`-1`, `65536`, `1.5`, `true`, `null`, `"35-20"`, `"0-65536"`, `"65536-65537"`, `"-1-25"`, `"+25"`, `" 25"`, `"1-2-3"`, `""`, `{}`, `[]`} {
		var parsed option.WireGuardPeer
		if json.Unmarshal([]byte(`{"persistent_keepalive_interval":`+input+`}`), &parsed) == nil {
			t.Errorf("invalid synthetic interval accepted: %s", input)
		}
	}
}
