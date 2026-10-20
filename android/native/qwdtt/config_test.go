package main

import (
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTunaPrivateConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "input")
	makeFlags := func() *flag.FlagSet {
		f := flag.NewFlagSet("test", flag.ContinueOnError)
		for _, k := range []string{"vk", "password", "socks-user", "socks-pass"} {
			f.String(k, "", "")
		}
		return f
	}
	raw := `{"vk":"synthetic-hash","password":"synthetic-password","socks-user":"local","socks-pass":"test"}`
	if err := os.WriteFile(path, []byte(raw), 0600); err != nil {
		t.Fatal(err)
	}
	f := makeFlags()
	if err := tunaLoadConfig(path, f); err != nil {
		t.Fatal(err)
	}
	if f.Lookup("password").Value.String() != "synthetic-password" {
		t.Fatal("configuration missing")
	}
	conflict := makeFlags()
	_ = conflict.Set("password", "argv-secret")
	if tunaLoadConfig(path, conflict) == nil {
		t.Fatal("ambiguous credentials accepted")
	}
	for _, bad := range []string{`{"peer":"secret.invalid"}`, raw + `{}`, `null`, `{"password":2}`, strings.Repeat(" ", tunaConfigLimit+1)} {
		if err := os.WriteFile(path, []byte(bad), 0600); err != nil {
			t.Fatal(err)
		}
		f = makeFlags()
		err := tunaLoadConfig(path, f)
		if err == nil || err.Error() != "private configuration rejected" {
			t.Fatal("unsafe error or accepted input")
		}
		if f.Lookup("password").Value.String() != "" {
			t.Fatal("partial configuration applied")
		}
	}
}
