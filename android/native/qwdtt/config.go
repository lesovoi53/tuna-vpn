// TUNA configuration overlay. Upstream attribution remains in the pinned source.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"os"
)

const tunaConfigLimit = 128 * 1024

// A descriptor path keeps credentials out of argv while stdin stays available
// for the existing CAPTCHA, TURN credential and pause/resume protocol.
func tunaLoadConfig(path string, flags *flag.FlagSet) error {
	if path == "" {
		return nil
	}
	rejected := errors.New("private configuration rejected")
	f, err := os.Open(path)
	if err != nil {
		return rejected
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, tunaConfigLimit+1))
	if err != nil || len(raw) > tunaConfigLimit {
		return rejected
	}
	var config map[string]string
	decoder := json.NewDecoder(bytes.NewReader(raw))
	if decoder.Decode(&config) != nil || config == nil {
		return rejected
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return rejected
	}
	allowed := map[string]bool{"vk": true, "password": true, "socks-user": true, "socks-pass": true}
	conflict := false
	flags.Visit(func(f *flag.Flag) {
		if allowed[f.Name] {
			conflict = true
		}
	})
	if conflict {
		return rejected
	}
	for key := range config {
		if !allowed[key] || flags.Lookup(key) == nil {
			return rejected
		}
	}
	for key, value := range config {
		if flags.Set(key, value) != nil {
			return rejected
		}
	}
	return nil
}
