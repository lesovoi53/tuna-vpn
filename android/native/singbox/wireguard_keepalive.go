// WireGuard keepalive intervals are preserved through JSON and UAPI.
package common

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
)

// WireGuardKeepalive retains the existing uint16 seconds bound and adds inclusive ranges.
// It never selects a value: the pinned WireGuard engine owns interval sampling.
type WireGuardKeepalive string

func WireGuardKeepaliveFromSeconds(value uint16) WireGuardKeepalive {
	return WireGuardKeepalive(strconv.FormatUint(uint64(value), 10))
}
func (i WireGuardKeepalive) String() string {
	if i == "" {
		return "0"
	}
	return string(i)
}
func (i WireGuardKeepalive) IsZero() bool { return i == "" || i == "0" }

func (i *WireGuardKeepalive) UnmarshalJSON(data []byte) error {
	value := string(data)
	if len(data) > 0 && data[0] == '"' {
		if err := json.Unmarshal(data, &value); err != nil {
			return errors.New("invalid keepalive interval")
		}
	}
	parts := strings.Split(value, "-")
	if len(parts) < 1 || len(parts) > 2 {
		return errors.New("invalid keepalive interval")
	}
	bounds := make([]uint64, len(parts))
	for index, part := range parts {
		if part == "" {
			return errors.New("invalid keepalive interval")
		}
		for _, digit := range part {
			if digit < '0' || digit > '9' {
				return errors.New("invalid keepalive interval")
			}
		}
		number, err := strconv.ParseUint(part, 10, 16)
		if err != nil {
			return errors.New("keepalive interval outside 0..65535")
		}
		bounds[index] = number
	}
	last := bounds[len(bounds)-1]
	if bounds[0] > last {
		return errors.New("reversed keepalive interval")
	}
	canonical := strconv.FormatUint(bounds[0], 10)
	if bounds[0] != last {
		canonical += "-" + strconv.FormatUint(last, 10)
	}
	*i = WireGuardKeepalive(canonical)
	return nil
}

func (i WireGuardKeepalive) MarshalJSON() ([]byte, error) {
	if strings.Contains(i.String(), "-") {
		return json.Marshal(i.String())
	}
	return []byte(i.String()), nil
}
