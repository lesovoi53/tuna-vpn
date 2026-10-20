package main

import (
	"bytes"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTunaPrivateArgs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "input")
	for _, tc := range []struct {
		input string
		valid bool
	}{
		{`["-proxy-pass","synthetic-secret","-mux","2"]`, true},
		{`["-mux","synthetic-secret"]`, false},
		{`["-args-config","nested"]`, false},
		{`["-proxy-pass","x"] {}`, false},
		{`null`, false},
		{strings.Repeat(" ", 4*1024*1024+1), false},
	} {
		if err := os.WriteFile(path, []byte(tc.input), 0600); err != nil {
			t.Fatal(err)
		}
		flags := flag.NewFlagSet("test", flag.ContinueOnError)
		password := flags.String("proxy-pass", "", "")
		flags.Int("mux", 0, "")
		var output bytes.Buffer
		flags.SetOutput(&output)
		err := tunaParseArgs(flags, []string{"-args-config", path})
		if (err == nil) != tc.valid {
			t.Fatalf("acceptance mismatch: %v", err)
		}
		if tc.valid && *password != "synthetic-secret" {
			t.Fatal("lost private argument")
		}
		if output.Len() != 0 {
			t.Fatal("private argument leaked into parser diagnostics")
		}
	}
}
