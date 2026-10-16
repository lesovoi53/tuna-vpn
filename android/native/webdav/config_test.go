package main
import("encoding/json";"os";"path/filepath";"testing";"time")
func TestTunaJSONCredentialsAreLiteral(t *testing.T){
 secret:="x:\"%@\\\r\n\tЮникод\u0001";file:=filepath.Join(t.TempDir(),"profile.json")
 raw,e:=json.Marshal(map[string]any{"mode":"client","socks-listen":"127.0.0.1:1080","timeout":"60s","backends":[]map[string]string{{"url":"https://example.invalid/dav","login":"user","password":secret}},"tuning":map[string]any{"poll-min":"200ms","chunk-size":131071}});if e!=nil{t.Fatal(e)};if e=os.WriteFile(file,raw,0600);e!=nil{t.Fatal(e)}
 cfg,e:=LoadConfig(file);if e!=nil{t.Fatal(e)};if cfg.Backends[0].Password!=secret||time.Duration(*cfg.Timeout)!=time.Minute||*cfg.Tuning.ChunkSize!=131071{t.Fatal("JSON values changed")}
}
