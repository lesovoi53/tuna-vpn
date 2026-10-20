package client

import (
	"encoding/json"
	"fmt"
)

// Set once by the isolated adapter before bootstrap. Normal runtime stays silent.
var TunaDiagnosticMode bool

// Numeric allow-list only: never serialize ClientConfig, domains, keys or credentials.
func (c *Client) tunaEmitDiagnosticPolicy() {
	if !TunaDiagnosticMode || c == nil {
		return
	}
	parameters := map[string]interface{}{
		"resolverCount":            len(c.cfg.Resolvers),
		"strategy":                 c.cfg.ResolverBalancingStrategy,
		"packetDuplication":        c.cfg.PacketDuplicationCount,
		"setupDuplication":         c.cfg.SetupPacketDuplicationCount,
		"rxTxWorkers":              c.cfg.RX_TX_Workers,
		"processWorkers":           c.cfg.TunnelProcessWorkers,
		"maxUploadMtu":             c.cfg.MaxUploadMTU,
		"maxDownloadMtu":           c.cfg.MaxDownloadMTU,
		"batch":                    c.cfg.MaxPacketsPerBatch,
		"window":                   c.cfg.ARQWindowSize,
		"dataNackGap":              c.cfg.ARQDataNackMaxGap,
		"compressionMinSize":       c.cfg.CompressionMinSize,
		"initialRtoSeconds":        c.cfg.ARQInitialRTOSeconds,
		"controlInitialRtoSeconds": c.cfg.ARQControlInitialRTOSeconds,
		"pingSeconds":              c.cfg.PingAggressiveIntervalSeconds,
	}
	if data, err := json.Marshal(parameters); err == nil {
		fmt.Println("TUNA_MASTER_POLICY " + string(data))
	}
}
