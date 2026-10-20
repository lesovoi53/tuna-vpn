
// TUNA scan-only adapter. Reuses upstream wire builders, random probe codes and
// response validation. No runtime balancing/session state is mutated.
type TunaResolverScanResult struct {
	Resolver   string `json:"resolver"`
	Domain     string `json:"domain"`
	Valid      bool   `json:"valid"`
	Upload     int    `json:"upload"`
	Download   int    `json:"download"`
	LatencyMs  int64  `json:"latencyMs"`
	Attempts   int    `json:"attempts"`
	Successes  int    `json:"successes"`
	MTUCheckMs int64  `json:"mtuCheckMs"`
}

func tunaRepeatProbe(ctx context.Context, count int, probe func() (bool, time.Duration, error)) (int, int, int64) {
	attempts := 0
	rtts := make([]time.Duration, 0, count)
	for range count {
		if ctx.Err() != nil {
			break
		}
		attempts++
		ok, rtt, err := probe()
		if err == nil && ok && rtt >= 0 {
			rtts = append(rtts, rtt)
		}
	}
	if len(rtts) == 0 {
		return attempts, 0, 0
	}
	sort.Slice(rtts, func(i, j int) bool { return rtts[i] < rtts[j] })
	median := rtts[len(rtts)/2]
	if len(rtts)%2 == 0 {
		median = (rtts[len(rtts)/2-1] + median) / 2
	}
	return attempts, len(rtts), median.Milliseconds()
}

// ScanResolverConnections emits every tested pair, including MTU rejections.
// The five samples are individual download round trips at the discovered MTU;
// timeout/invalid responses count as losses, MTU discovery packets do not.
func (c *Client) ScanResolverConnections(ctx context.Context, emit func(TunaResolverScanResult)) error {
	connections := c.balancer.AllConnections()
	caps := c.precomputeUploadCaps()
	jobs := make(chan Connection)
	results := make(chan TunaResolverScanResult, 8)
	var workers sync.WaitGroup
	for range min(8, len(connections)) {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for conn := range jobs {
				if ctx.Err() != nil {
					return
				}
				result := c.scanTunaConnection(ctx, conn, caps[conn.Domain])
				select {
				case results <- result:
				case <-ctx.Done():
					return
				}
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, conn := range connections {
			select {
			case jobs <- conn:
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() {
		workers.Wait()
		close(results)
	}()
	for result := range results {
		emit(result)
	}
	return ctx.Err()
}

func (c *Client) scanTunaConnection(parent context.Context, conn Connection, maxUpload int) TunaResolverScanResult {
	ctx, cancel := context.WithTimeout(parent, 25*time.Second)
	defer cancel()
	result := TunaResolverScanResult{Resolver: conn.ResolverLabel, Domain: conn.Domain}
	start := time.Now()
	mtu, rejection := c.probeConnectionMTU(ctx, conn, maxUpload)
	result.MTUCheckMs = time.Since(start).Milliseconds()
	result.Upload, result.Download = mtu.UploadBytes, mtu.DownloadBytes
	if rejection != mtuRejectNone || ctx.Err() != nil {
		return result
	}
	transport, err := newUDPQueryTransport(conn.ResolverLabel)
	if err != nil {
		return result
	}
	defer transport.conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = transport.conn.Close() })
	defer stop()
	timeout := c.mtuTestTimeout
	if timeout > 1500*time.Millisecond {
		timeout = 1500*time.Millisecond
	}
	result.Attempts, result.Successes, result.LatencyMs = tunaRepeatProbe(ctx, 5, func() (bool, time.Duration, error) {
		return c.sendDownloadMTUProbe(ctx, conn, transport, mtu.DownloadBytes, mtu.UploadBytes,
			timeout, mtuProbeOptions{Quiet: true})
	})
	result.Valid = result.Attempts == 5 && result.Successes > 0
	return result
}
