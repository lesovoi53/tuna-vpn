package route

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/service/pause"
)

type tunaStartupRead struct {
	release chan struct{}
	once    sync.Once
}

func (r *tunaStartupRead) finish() { r.once.Do(func() { close(r.release) }) }

type tunaStartupPlatform struct {
	adapter.PlatformInterface
	reads chan *tunaStartupRead
}

func (*tunaStartupPlatform) UsePlatformNetworkInterfaces() bool { return false }
func (*tunaStartupPlatform) UsePlatformWIFIMonitor() bool       { return true }
func (p *tunaStartupPlatform) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	read := &tunaStartupRead{release: make(chan struct{})}
	p.reads <- read
	select {
	case <-read.release:
	case <-ctx.Done():
	}
	return adapter.WIFIState{}
}

type tunaStartupPause struct{ pause.Manager }

func (tunaStartupPause) NetworkWake()  {}
func (tunaStartupPause) NetworkPause() {}

type tunaStartupEndpoints struct{ adapter.EndpointManager }

func (tunaStartupEndpoints) Endpoints() []adapter.Endpoint { return nil }

type tunaStartupInbounds struct{ adapter.InboundManager }

func (tunaStartupInbounds) Inbounds() []adapter.Inbound { return nil }

type tunaStartupOutbounds struct{ adapter.OutboundManager }

func (tunaStartupOutbounds) Outbounds() []adapter.Outbound { return nil }

type tunaStartupRouter struct {
	adapter.Router
	resets chan struct{}
}

func (r *tunaStartupRouter) ResetNetwork() { r.resets <- struct{}{} }

func tunaNewStartupManager(t *testing.T) (*NetworkManager, *tunaStartupPlatform, <-chan struct{}, context.CancelFunc) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	platform := &tunaStartupPlatform{reads: make(chan *tunaStartupRead, 8)}
	router := &tunaStartupRouter{resets: make(chan struct{}, 8)}
	manager := &NetworkManager{
		ctx: ctx, logger: log.NewNOPFactory().Logger(),
		platformInterface: platform, pauseManager: tunaStartupPause{},
		router: router, endpoint: tunaStartupEndpoints{},
		inbound: tunaStartupInbounds{}, outbound: tunaStartupOutbounds{},
	}
	return manager, platform, router.resets, cancel
}

func tunaAwaitStartupRead(t *testing.T, platform *tunaStartupPlatform) *tunaStartupRead {
	t.Helper()
	select {
	case read := <-platform.reads:
		t.Cleanup(read.finish)
		return read
	case <-time.After(time.Second):
		t.Fatal("interface callback did not reach network discovery")
		return nil
	}
}

func tunaAwaitStartup(t *testing.T, done <-chan error) error {
	t.Helper()
	select {
	case err := <-done:
		return err
	case <-time.After(time.Second):
		t.Fatal("network startup did not finish")
		return nil
	}
}

func TestTunaNetworkStartupWaitsForInitialInterface(t *testing.T) {
	r, platform, resets, _ := tunaNewStartupManager(t)
	iface := &control.Interface{Name: "startup-test", Index: 1}
	r.notifyInterfaceUpdate(iface, 0)
	initial := tunaAwaitStartupRead(t, platform)
	started := make(chan error, 1)
	go func() { started <- r.Start(adapter.StartStatePostStart) }()
	select {
	case err := <-started:
		t.Fatalf("startup admitted traffic before initial interface discovery completed: %v", err)
	case <-time.After(30 * time.Millisecond):
	}
	initial.finish()
	if err := tunaAwaitStartup(t, started); err != nil {
		t.Fatal(err)
	}
	select {
	case <-resets:
		t.Fatal("initial interface discovery reset live transports")
	default:
	}
	// A subsequent real network change must still invalidate transports.
	r.notifyInterfaceUpdate(&control.Interface{Name: "changed-test", Index: 2}, 0)
	tunaAwaitStartupRead(t, platform).finish()
	select {
	case <-resets:
	case <-time.After(time.Second):
		t.Fatal("a network change after startup did not reset transports")
	}
}

func TestTunaNetworkStartupWaitsForReplacementInterface(t *testing.T) {
	r, platform, resets, _ := tunaNewStartupManager(t)
	r.notifyInterfaceUpdate(&control.Interface{Name: "first", Index: 1}, 0)
	tunaAwaitStartupRead(t, platform)
	started := make(chan error, 1)
	go func() { started <- r.Start(adapter.StartStatePostStart) }()
	// Replacing the in-progress callback cancels it, but its completion must
	// not open the startup gate while the replacement is still discovering.
	r.notifyInterfaceUpdate(&control.Interface{Name: "replacement", Index: 2}, 0)
	replacement := tunaAwaitStartupRead(t, platform)
	select {
	case err := <-started:
		t.Fatalf("startup did not wait for replacement interface: %v", err)
	case <-time.After(30 * time.Millisecond):
	}
	replacement.finish()
	if err := tunaAwaitStartup(t, started); err != nil {
		t.Fatal(err)
	}
	select {
	case <-resets:
		t.Fatal("replacement initial interface reset live transports")
	default:
	}
}

func TestTunaNetworkStartupCancellation(t *testing.T) {
	r, platform, _, cancel := tunaNewStartupManager(t)
	r.notifyInterfaceUpdate(&control.Interface{Name: "cancelled", Index: 1}, 0)
	tunaAwaitStartupRead(t, platform)
	cancel()
	if err := r.Start(adapter.StartStatePostStart); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled startup returned %v, want context.Canceled", err)
	}
}

func TestTunaNetworkStartupDoesNotSkipFirstLaterChange(t *testing.T) {
	r, platform, resets, _ := tunaNewStartupManager(t)
	if err := r.Start(adapter.StartStatePostStart); err != nil {
		t.Fatal(err)
	}
	r.notifyInterfaceUpdate(&control.Interface{Name: "first-after-start", Index: 1}, 0)
	tunaAwaitStartupRead(t, platform).finish()
	select {
	case <-resets:
	case <-time.After(time.Second):
		t.Fatal("first interface event after startup was incorrectly ignored")
	}
}
