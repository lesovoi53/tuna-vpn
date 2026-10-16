//go:build linux

package main

import (
	"context"
	"encoding/json"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/xtls/xray-core/app/observatory"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/extension"
	"github.com/xtls/xray-core/features/stats"
)

// startStatsSocket listens on an abstract Unix domain socket.
// Only processes with the same UID (i.e. the owning app) are allowed to connect.
// Commands (sent as a single line): stats | status | check [n]
// Each command returns a compact JSON line, then the connection is closed.
func startStatsSocket(name string, server *core.Instance, outboundTags []string) {
	if name == "" {
		return
	}
	// "\x00" prefix = Linux abstract socket namespace (no filesystem entry).
	ln, err := net.Listen("unix", "\x00"+name)
	if err != nil {
		log.Printf("stats socket: %v", err)
		return
	}
	myUID := uint32(os.Getuid())
	log.Printf("stats socket: listening on @%s", name)
	go func() {
		defer ln.Close()
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go serveStatsConn(conn, myUID, server, outboundTags)
		}
	}()
}

func peerUID(conn net.Conn) (uint32, bool) {
	uc, ok := conn.(*net.UnixConn)
	if !ok {
		return 0, false
	}
	raw, err := uc.SyscallConn()
	if err != nil {
		return 0, false
	}
	var uid uint32
	var valid bool
	_ = raw.Control(func(fd uintptr) {
		cred, err := syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
		if err != nil {
			return
		}
		uid = uint32(cred.Uid)
		valid = true
	})
	return uid, valid
}

func serveStatsConn(conn net.Conn, myUID uint32, server *core.Instance, outboundTags []string) {
	defer conn.Close()

	uid, ok := peerUID(conn)
	if !ok || uid != myUID {
		return
	}

	conn.SetDeadline(time.Now().Add(5 * time.Second))

	buf := make([]byte, 32)
	n, err := conn.Read(buf)
	if err != nil {
		return
	}
	parts := strings.Fields(strings.TrimSpace(string(buf[:n])))
	if len(parts) == 0 {
		return
	}

	var resp []byte
	switch parts[0] {
	case "stats":
		resp = socketStatsJSON(server, outboundTags)
	case "status":
		resp = socketStatusJSON(server)
	case "check":
		rounds := 1
		if len(parts) > 1 {
			if v, err := strconv.Atoi(parts[1]); err == nil && v > 0 && v <= 10 {
				rounds = v
			}
		}
		resp = socketCheckJSON(server, rounds)
	default:
		resp = []byte(`{"error":"unknown command"}`)
	}
	conn.Write(append(resp, '\n'))
}

func socketStatsJSON(server *core.Instance, outboundTags []string) []byte {
	var rx, tx int64
	if st := server.GetFeature(stats.ManagerType()); st != nil {
		if sm, ok := st.(stats.Manager); ok {
			for _, tag := range outboundTags {
				if c := sm.GetCounter("outbound>>>" + tag + ">>>traffic>>>downlink"); c != nil {
					rx += c.Value()
				}
				if c := sm.GetCounter("outbound>>>" + tag + ">>>traffic>>>uplink"); c != nil {
					tx += c.Value()
				}
			}
		}
	}
	b, _ := json.Marshal(map[string]int64{"tx_bytes": tx, "rx_bytes": rx})
	return b
}

func socketStatusJSON(server *core.Instance) []byte {
	type pingStats struct {
		AvgMs int64 `json:"avg_ms"`
		MinMs int64 `json:"min_ms"`
		MaxMs int64 `json:"max_ms"`
		Total int64 `json:"total"`
		Fail  int64 `json:"fail"`
	}
	type outboundInfo struct {
		Tag   string     `json:"tag"`
		Alive bool       `json:"alive"`
		Ping  *pingStats `json:"ping,omitempty"`
	}
	type statusResp struct {
		Outbounds []outboundInfo `json:"outbounds"`
		Active    string         `json:"active"`
	}

	resp := statusResp{Active: "none"}
	feat := server.GetFeature(extension.ObservatoryType())
	obs, ok := feat.(extension.Observatory)
	if !ok {
		b, _ := json.Marshal(resp)
		return b
	}
	result, err := obs.GetObservation(context.Background())
	if err != nil {
		b, _ := json.Marshal(resp)
		return b
	}
	or, ok := result.(*observatory.ObservationResult)
	if !ok {
		b, _ := json.Marshal(resp)
		return b
	}

	bestDelay := int64(-1)
	for _, s := range or.GetStatus() {
		info := outboundInfo{Tag: s.GetOutboundTag(), Alive: s.GetAlive()}
		if hp := s.GetHealthPing(); hp != nil {
			info.Ping = &pingStats{
				AvgMs: hp.GetAverage() / int64(time.Millisecond),
				MinMs: hp.GetMin() / int64(time.Millisecond),
				MaxMs: hp.GetMax() / int64(time.Millisecond),
				Total: hp.GetAll(),
				Fail:  hp.GetFail(),
			}
		}
		if s.GetAlive() {
			if delay := s.GetDelay(); bestDelay < 0 || delay < bestDelay {
				bestDelay = delay
				resp.Active = s.GetOutboundTag()
			}
		}
		resp.Outbounds = append(resp.Outbounds, info)
	}
	b, _ := json.Marshal(resp)
	return b
}

func socketCheckJSON(server *core.Instance, n int) []byte {
	feat := server.GetFeature(extension.ObservatoryType())
	obs, ok := feat.(extension.BurstObservatory)
	if !ok {
		return []byte(`{"error":"not in dual-route mode"}`)
	}
	tags := []string{"local", "direct"}
	go func() {
		for i := 0; i < n; i++ {
			obs.Check(tags)
		}
	}()
	b, _ := json.Marshal(map[string]any{"status": "check started", "rounds": n})
	return b
}
