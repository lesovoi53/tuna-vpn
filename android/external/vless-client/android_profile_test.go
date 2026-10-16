package main

import (
    "os"
    "path/filepath"
    "strings"
    "testing"
    "github.com/xtls/xray-core/core"
    "github.com/xtls/xray-core/infra/conf/serial"
)

// Kotlin generator -> wrapper parser -> pinned Xray core, with no remote connection.
func TestAndroidGeneratedProfiles(t *testing.T) {
    directory := os.Getenv("TUNA_ANDROID_FIXTURES")
    if directory == "" { t.Skip("Set TUNA_ANDROID_FIXTURES to app/build/xray-test-configs") }
    files, err := filepath.Glob(filepath.Join(directory,"*.json"))
    if err != nil || len(files)==0 { t.Fatal("Missing Android fixtures",err) }
    for _, path := range files { t.Run(filepath.Base(path),func(t *testing.T) {
        fc, err := loadFileConfig(path,true); if err!=nil { t.Fatal(err) }
        endpoint, err := parseProxyLink(fc.Link); if err!=nil { t.Fatal(err) }
        mux := 0; if fc.Mux!=nil { mux=*fc.Mux }
        raw, err := buildXrayConfig([]*ProxyConfig{endpoint},"","","",fc.Listen,"",strings.Split(fc.DNS,","),false,0,mux,fc.ProxyUser,fc.ProxyPass,"","","",false,false,false)
        if err!=nil { t.Fatal(err) }
        config,err:=serial.LoadJSONConfig(strings.NewReader(string(raw)));if err!=nil { t.Fatal(err) }
        instance,err:=core.New(config);if err!=nil { t.Fatal(err) };instance.Close()
    }) }
}
