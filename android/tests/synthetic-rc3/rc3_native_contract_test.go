package main

import (
    "testing"
    "regexp"
    "strings"
    "github.com/xtls/xray-core/core"
    "github.com/xtls/xray-core/infra/conf/serial"
    "os"
    "path/filepath"
    "github.com/xtls/xray-core/common/geodata"
    xtls "github.com/xtls/xray-core/transport/internet/tls"
)

func TestAuditRc3EmptyFingerprintIsChrome(t *testing.T) {
    if *xtls.GetFingerprint("") != *xtls.GetFingerprint("chrome") {
        t.Fatal("empty fingerprint no longer defaults to chrome; reassess import finding")
    }
}

func TestAuditRc3GeoIPInverseAndCaseAreSupported(t *testing.T) {
    directory := t.TempDir()
    entry := append([]byte{10, 7}, []byte("PRIVATE")...)
    entry = append(entry, []byte{18, 8, 10, 4, 10, 0, 0, 0, 16, 8}...)
    if err := os.WriteFile(filepath.Join(directory, "geoip.dat"), append([]byte{10, byte(len(entry))}, entry...), 0600); err != nil { t.Fatal(err) }
    t.Setenv("xray.location.asset", directory)
    for _, value := range []string{"geoip:private", "geoip:!private", "geoip:PRIVATE"} {
        t.Run(value, func(t *testing.T) {
            rules, err := geodata.ParseIPRules([]string{value})
            if err != nil { t.Fatal(err) }
            if _, err = geodata.IPReg.BuildIPMatcher(rules); err != nil { t.Fatal(err) }
        })
    }
}

func TestAuditRc3InvalidRegexpIsRejectedByCore(t *testing.T) {
    rules, err := geodata.ParseDomainRules([]string{"regexp:["}, geodata.Domain_Substr)
    if err != nil { return }
    if _, err = geodata.DomainReg.BuildDomainMatcher(rules); err == nil {
        t.Fatal("core unexpectedly accepted invalid regular expression")
    }
}

func TestAuditRc3RegexpDialect(t *testing.T) {
    for _, expression := range []string{`[`, `\`, `(?=x)x`, `(x)\1`, `\C`, `a{1001}`} {
        if _, err := regexp.Compile(expression); err == nil { t.Errorf("accepted %q", expression) }
    }
    for _, expression := range []string{`^example\.org$`, `(?i)example`, `(?P<host>example)`, `\p{Greek}+`, `\Q\C\E`, `\\C`, `a{1,3}`} {
        if _, err := regexp.Compile(expression); err != nil { t.Errorf("rejected %q: %v", expression, err) }
    }
}

func TestAuditRc3RegexpRangesStayOneMatcher(t *testing.T) {
    raw, err := buildSocks5XrayConfig("127.0.0.1:1", "", "", "127.0.0.1:1", "", []string{"192.0.2.53"}, false, "", "", "", "", false)
    if err != nil { t.Fatal(err) }
    policy := `{"mode":"SELECTED_DIRECT","rules":[{"match":"regexp:^a{1,3}\\.invalid$","action":"BLOCK","enabled":true}]}`
    path := filepath.Join(t.TempDir(), "policy.json")
    if err = os.WriteFile(path, []byte(policy), 0600); err != nil { t.Fatal(err) }
    raw, err = applyTunaPolicy(raw, path)
    if err != nil { t.Fatal(err) }
    parsed, err := serial.LoadJSONConfig(strings.NewReader(string(raw)))
    if err != nil { t.Fatal(err) }
    server, err := core.New(parsed)
    if err != nil { t.Fatal(err) }
    server.Close()
    if !strings.Contains(string(raw), `regexp:^a{1,3}\\.invalid$`) { t.Fatal("regex was split or changed") }
}
