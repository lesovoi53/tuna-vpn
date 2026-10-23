// TUNA adapter for MIT-licensed MasterDnsVPN. No protocol changes.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"net/netip"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func main() {
	path := flag.String("config", "", "Private TOML configuration")
	resolvers := flag.String("resolvers", "", "Explicit resolver file (including memory FD path)")
	scan := flag.Bool("scan", false, "Run native resolver and MTU checks only")
	diagnostic := flag.Bool("diagnostic", false, "Isolated authenticated loopback runtime retaining resolver endpoint ports")
	validate := flag.Bool("validate", false, "Validate configuration without networking")
	poolPath := flag.String("pool", "", "Private memory JSON for independent authenticated SOCKS pool")
	poolChild := flag.Bool("pool-child", false, "Internal private pool child")
	flag.Parse()
	if *poolPath != "" {
		if *scan || *diagnostic || *path != "" || *resolvers != "" || *poolChild {
			fmt.Println("TUNA_MASTER_ERROR pool flags rejected")
			os.Exit(1)
		}
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		if err := runMasterPool(ctx, *poolPath, *validate); err != nil {
			fmt.Println("TUNA_MASTER_ERROR pool rejected")
			os.Exit(1)
		}
		return
	}
	if *poolChild {
		if *scan || *diagnostic || !poolPrivatePath(*path) || !poolPrivatePath(*resolvers) {
			fmt.Println("TUNA_MASTER_ERROR pool child flags rejected")
			os.Exit(1)
		}
		client.TunaPoolChildMode = true
	}
	overrides := config.ClientConfigOverrides{}
	if *resolvers != "" {
		overrides.ResolversFilePath = resolvers
	}
	cfg, err := config.LoadClientConfigWithOverrides(*path, overrides)
	if err != nil {
		fmt.Println("TUNA_MASTER_ERROR configuration rejected")
		os.Exit(1)
	}
	if err = prepareRuntimeResolverEndpoints(&cfg); err != nil {
		fmt.Println("TUNA_MASTER_ERROR resolver endpoints rejected")
		os.Exit(1)
	}
	if *validate {
		fmt.Println("TUNA_MASTER_VALID")
		return
	}
	if *diagnostic {
		if err = validateDiagnosticConfig(cfg); err != nil {
			fmt.Println("TUNA_MASTER_ERROR diagnostic configuration rejected")
			os.Exit(1)
		}
		client.TunaDiagnosticMode = true
	}
	app, err := client.BootstrapLoadedConfig(cfg, "")
	if err != nil {
		fmt.Println("TUNA_MASTER_ERROR bootstrap failed")
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if *scan {
		ctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
		defer cancel()
		err = app.ScanResolverConnections(ctx, func(result client.TunaResolverScanResult) {
			b, _ := json.Marshal(result)
			fmt.Println("TUNA_RESOLVER " + string(b))
		})
		if err != nil || ctx.Err() != nil {
			fmt.Println("TUNA_SCAN_FAILED")
			os.Exit(2)
		}
		fmt.Println("TUNA_SCAN_DONE")
		return
	}
	if err = app.Run(ctx); err != nil {
		fmt.Println("TUNA_MASTER_ERROR runtime failed")
		os.Exit(1)
	}
}

func validateDiagnosticConfig(cfg config.ClientConfig) error {
	listen, err := netip.ParseAddr(cfg.ListenIP)
	if err != nil || !listen.IsLoopback() || !cfg.SOCKS5Auth || cfg.SOCKS5User == "" || cfg.SOCKS5Pass == "" || cfg.LocalDNSEnabled || cfg.ProtocolType != "SOCKS5" {
		return fmt.Errorf("diagnostic requires authenticated loopback SOCKS only")
	}
	for _, resolver := range cfg.Resolvers {
		ip, err := netip.ParseAddr(resolver.IP)
		if err != nil || !ip.IsLoopback() {
			return fmt.Errorf("diagnostic requires loopback relays")
		}
	}
	return nil
}

func prepareRuntimeResolverEndpoints(cfg *config.ClientConfig) error {
	// Explicit per-domain selections already provide their exact canonical union.
	// Otherwise restore IP+port identity after upstream's IP-only file loader,
	// for regular connections and pool children as well as isolated scans.
	if cfg.TunaDomainResolvers != nil {
		return nil
	}
	endpoints, err := scanResolverEndpoints(cfg.ResolversPath())
	if err != nil {
		return err
	}
	cfg.Resolvers = endpoints
	return nil
}

func scanResolverEndpoints(path string) ([]config.ResolverAddress, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 256), 4096)
	endpoints := make([]config.ResolverAddress, 0, 128)
	seen := make(map[netip.AddrPort]struct{}, 128)
	lines := 0
	for scanner.Scan() {
		lines++
		if lines > 1024 {
			return nil, fmt.Errorf("scan resolver file exceeds line limit")
		}
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		endpoint, err := netip.ParseAddrPort(line)
		if err != nil {
			address, parseErr := netip.ParseAddr(line)
			if parseErr != nil {
				return nil, fmt.Errorf("scan resolver must be a literal IP endpoint")
			}
			endpoint = netip.AddrPortFrom(address, 53)
		}
		if endpoint.Port() == 0 || endpoint.Addr().Zone() != "" {
			return nil, fmt.Errorf("invalid scan resolver endpoint")
		}
		endpoint = netip.AddrPortFrom(endpoint.Addr().Unmap(), endpoint.Port())
		if _, exists := seen[endpoint]; exists {
			continue
		}
		if len(endpoints) == 128 {
			return nil, fmt.Errorf("too many scan resolver endpoints")
		}
		seen[endpoint] = struct{}{}
		endpoints = append(endpoints, config.ResolverAddress{IP: endpoint.Addr().String(), Port: int(endpoint.Port())})
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	if len(endpoints) == 0 {
		return nil, fmt.Errorf("no scan resolver endpoints")
	}
	return endpoints, nil
}
