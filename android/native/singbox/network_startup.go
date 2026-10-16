package route

import "context"

// Register the callback before launching its goroutine. Startup must wait for
// the latest registered update even if that goroutine has not run yet. Capture
// whether this was a live network change under the same lock as the startup
// transition; an initial callback must never reset newly admitted traffic.
func (r *NetworkManager) tunaBeginInterfaceUpdate() (context.Context, func(), bool) {
	ctx, cancel := context.WithCancel(r.ctx)
	done := make(chan struct{})
	r.interfaceUpdateAccess.Lock()
	previousCancel := r.interfaceUpdateCancel
	r.interfaceUpdateCancel = cancel
	r.interfaceUpdateDone = done
	resetNetwork := r.started
	r.interfaceUpdateAccess.Unlock()
	if previousCancel != nil {
		previousCancel()
	}
	return ctx, func() {
		cancel()
		close(done)
	}, resetNetwork
}

// PostStart runs before the router opens its traffic gate. A replacement
// callback can cancel the one we were waiting for, so recheck the latest done
// channel while holding the callback registration lock before marking ready.
func (r *NetworkManager) tunaStartInterfaceUpdates() error {
	for {
		r.interfaceUpdateAccess.Lock()
		if err := r.ctx.Err(); err != nil {
			r.interfaceUpdateAccess.Unlock()
			return err
		}
		done := r.interfaceUpdateDone
		if done != nil {
			select {
			case <-done:
				done = nil
			default:
			}
		}
		if done == nil {
			r.started = true
			r.interfaceUpdateAccess.Unlock()
			return nil
		}
		r.interfaceUpdateAccess.Unlock()
		select {
		case <-done:
		case <-r.ctx.Done():
			return r.ctx.Err()
		}
	}
}
