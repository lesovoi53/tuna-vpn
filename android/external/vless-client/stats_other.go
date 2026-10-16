//go:build !linux

package main

import "github.com/xtls/xray-core/core"

func startStatsSocket(_ string, _ *core.Instance, _ []string) {}
