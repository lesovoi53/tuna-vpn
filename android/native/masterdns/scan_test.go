package client

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestTunaScanCountsFailedAndInvalidProbeSamples(t *testing.T) {
	n := 0
	attempts, successes, median := tunaRepeatProbe(context.Background(), 5, func() (bool, time.Duration, error) {
		n++
		switch n {
		case 1:
			return true, 90 * time.Millisecond, nil
		case 2:
			return false, 0, nil // A DNS reply that failed the upstream tunnel-code check.
		case 3:
			return false, 0, errors.New("timeout")
		case 4:
			return true, 30 * time.Millisecond, nil
		default:
			return true, 60 * time.Millisecond, nil
		}
	})
	if attempts != 5 || successes != 3 || median != 60 {
		t.Fatalf("got attempts=%d successes=%d median=%d", attempts, successes, median)
	}
}

func TestTunaScanCancelledProbesAreNotInvented(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	n := 0
	attempts, successes, median := tunaRepeatProbe(ctx, 5, func() (bool, time.Duration, error) {
		n++
		cancel()
		return false, 0, context.Canceled
	})
	if n != 1 || attempts != 1 || successes != 0 || median != 0 {
		t.Fatalf("canceled result: calls=%d attempts=%d successes=%d median=%d", n, attempts, successes, median)
	}
}

func TestTunaScanMedianEvenCountAndNoResponses(t *testing.T) {
	n := 0
	_, successes, median := tunaRepeatProbe(context.Background(), 2, func() (bool, time.Duration, error) {
		n++
		return true, time.Duration(n*20) * time.Millisecond, nil
	})
	if successes != 2 || median != 30 {
		t.Fatalf("even median: successes=%d median=%d", successes, median)
	}
	attempts, successes, median := tunaRepeatProbe(context.Background(), 5, func() (bool, time.Duration, error) {
		return false, 0, nil
	})
	if attempts != 5 || successes != 0 || median != 0 {
		t.Fatalf("no response: attempts=%d successes=%d median=%d", attempts, successes, median)
	}
}
