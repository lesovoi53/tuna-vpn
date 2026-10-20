// Set only by the independent private pool child adapter before bootstrap.
var TunaPoolChildMode bool

// The upstream DNS-only UDP listener otherwise binds wildcard and waits only
// for UDP traffic. Pool children bind their authenticated control's loopback IP
// and end the UDP association on control EOF or runtime cancellation.
func tunaPoolUDPWatch(ctx context.Context, control net.Conn, udp *net.UDPConn) func() {
	if !TunaPoolChildMode {
		return func() {}
	}
	ctx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(ctx, func() { udp.Close(); control.Close() })
	done := make(chan struct{})
	go func() {
		defer close(done)
		var buffer [1]byte
		control.Read(buffer[:])
		cancel()
	}()
	return func() {
		cancel()
		control.Close()
		udp.Close()
		<-done
		stop()
	}
}
