// TUNA adapter for MIT-licensed MasterDnsVPN. No protocol changes.
package main

import (
 "context"
 "encoding/json"
 "flag"
 "fmt"
 "os"
 "os/signal"
 "syscall"
 "time"
 "masterdnsvpn-go/internal/client"
 "masterdnsvpn-go/internal/config"
)

func main() {
 path := flag.String("config", "", "Private TOML configuration")
 scan := flag.Bool("scan", false, "Run native resolver and MTU checks only")
 validate := flag.Bool("validate", false, "Validate configuration without networking")
 flag.Parse()
 cfg, err := config.LoadClientConfig(*path)
 if err != nil { fmt.Println("TUNA_MASTER_ERROR configuration rejected"); os.Exit(1) }
 if *validate { fmt.Println("TUNA_MASTER_VALID"); return }
 app, err := client.BootstrapLoadedConfig(cfg, "")
 if err != nil { fmt.Println("TUNA_MASTER_ERROR bootstrap failed"); os.Exit(1) }
 ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
 defer stop()
 if *scan {
  ctx, cancel := context.WithTimeout(ctx, 3*time.Minute); defer cancel()
  // Snapshot stats are read only after the scanner joins its workers.
  err = app.RunInitialMTUTests(ctx)
  for _, c := range app.Balancer().AllConnections() {
   b, _ := json.Marshal(map[string]any{"resolver":c.ResolverLabel,"domain":c.Domain,"valid":c.IsValid,"upload":c.UploadMTUBytes,"download":c.DownloadMTUBytes,"latencyMs":c.MTUResolveTime.Milliseconds()})
   fmt.Println("TUNA_RESOLVER "+string(b))
  }
  if err != nil || ctx.Err()!=nil { fmt.Println("TUNA_SCAN_FAILED"); os.Exit(2) }
  fmt.Println("TUNA_SCAN_DONE"); return
 }
 if err = app.Run(ctx); err != nil { fmt.Println("TUNA_MASTER_ERROR runtime failed"); os.Exit(1) }
}
