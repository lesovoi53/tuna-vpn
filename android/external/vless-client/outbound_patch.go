package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/xtls/xray-core/infra/conf"
)

// Only the selected remote proxy can be extended. Routing, local listeners and
// authentication are owned by the application, including in expert mode.
func applyOutboundPatch(raw []byte, patch map[string]any) ([]byte, error) {
	for _, k := range []string{"tag", "protocol", "proxySettings", "sendThrough"} {
		if _, ok := patch[k]; ok {
			return nil, fmt.Errorf("outbound_patch.%s is managed by TUNA", k)
		}
	}
	var validate func(any, int) error
	validate = func(v any, depth int) error {
		if depth > 24 {
			return fmt.Errorf("outbound patch nesting limit")
		}
		if v == nil {
			return fmt.Errorf("null patch values are not supported")
		}
		switch x := v.(type) {
		case map[string]any:
			for k, v := range x {
				switch k {
				case "dialerProxy", "detour", "interface", "sockoptMark", "netns", "protect_path":
					return fmt.Errorf("%s is managed by TUNA", k)
				}
				if err := validate(v, depth+1); err != nil {
					return err
				}
			}
		case []any:
			for _, v := range x {
				if err := validate(v, depth+1); err != nil {
					return err
				}
			}
		}
		return nil
	}
	if err := validate(patch, 0); err != nil {
		return nil, err
	}
	var cfg map[string]any
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil, err
	}
	var merge func(map[string]any, map[string]any)
	merge = func(dst, src map[string]any) {
		for k, v := range src {
			child, ok := v.(map[string]any)
			old, exists := dst[k].(map[string]any)
			if ok && exists {
				merge(old, child)
			} else {
				dst[k] = v
			}
		}
	}
	count := 0
	for _, v := range cfg["outbounds"].([]any) {
		outbound := v.(map[string]any)
		switch outbound["protocol"] {
		case "vless", "trojan", "hysteria":
			merge(outbound, patch)
			encoded, err := json.Marshal(outbound)
			if err != nil {
				return nil, err
			}
			decoder := json.NewDecoder(bytes.NewReader(encoded))
			decoder.DisallowUnknownFields()
			var checked conf.OutboundDetourConfig
			if err := decoder.Decode(&checked); err != nil {
				return nil, fmt.Errorf("outbound fields: %w", err)
			}
			if _, patched := patch["settings"]; patched {
				var settings any
				switch outbound["protocol"] {
				case "vless":
					settings = &conf.VLessOutboundConfig{}
				case "trojan":
					settings = &conf.TrojanClientConfig{}
				case "hysteria":
					settings = &conf.HysteriaClientConfig{}
				}
				rawSettings, err := json.Marshal(outbound["settings"])
				if err != nil {
					return nil, err
				}
				decoder = json.NewDecoder(bytes.NewReader(rawSettings))
				decoder.DisallowUnknownFields()
				if err := decoder.Decode(settings); err != nil {
					return nil, fmt.Errorf("protocol settings: %w", err)
				}
			}
			count++
		}
	}
	if count != 1 && len(patch) > 0 {
		return nil, fmt.Errorf("outbound patch requires exactly one remote proxy")
	}
	return json.Marshal(cfg)
}
