//go:build linux || android

package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Opt-in end-to-end acceptance using two genuine upstream native servers.
// All configs/keys are synthetic; every listener and resolver is loopback.
func TestPoolTwoIndependentNativeServers(t *testing.T) {
	clientPath, serverPath := os.Getenv("TUNA_POOL_NATIVE_CLIENT"), os.Getenv("TUNA_POOL_NATIVE_SERVER")
	if clientPath == "" || serverPath == "" {
		t.Skip("set TUNA_POOL_NATIVE_CLIENT and TUNA_POOL_NATIVE_SERVER for isolated native fixture")
	}
	reserveTCP := func() string {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		address := listener.Addr().String()
		listener.Close()
		return address
	}
	front := reserveTCP()
	cfg := poolConfig{Version: 1, Listen: front, Username: "front", Password: "private", Strategy: "round_robin"}
	var files []*os.File
	fixtures := []*poolFixture{newPoolFixtureWithAuth(t, 'A', false), newPoolFixtureWithAuth(t, 'B', false)}
	servers := make([]*exec.Cmd, 0, 2)
	for i := range 2 {
		directory := t.TempDir()
		reserve, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		serverPort := reserve.LocalAddr().(*net.UDPAddr).Port
		reserve.Close()
		key := strings.Repeat(strconv.Itoa(i+1), 32)
		keyPath := filepath.Join(directory, "synthetic-key.txt")
		if err := os.WriteFile(keyPath, []byte(key), 0600); err != nil {
			t.Fatal(err)
		}
		domain := fmt.Sprintf("tunnel-%d.example.invalid", i)
		serverConfig := fmt.Sprintf(
			"UDP_HOST = \"127.0.0.1\"\nUDP_PORT = %d\nDOMAIN = [\"%s\"]\nDATA_ENCRYPTION_METHOD = 5\nENCRYPTION_KEY_FILE = \"synthetic-key.txt\"\n"+
				"DNS_UPSTREAM_SERVERS = [\"127.0.0.1:9\"]\nLOG_LEVEL = \"ERROR\"\nUSE_EXTERNAL_SOCKS5 = true\nFORWARD_IP = \"127.0.0.1\"\nFORWARD_PORT = %d\n",
			serverPort, domain, fixtures[i].listener.Addr().(*net.TCPAddr).Port)
		serverConfigPath := filepath.Join(directory, "server.toml")
		if err := os.WriteFile(serverConfigPath, []byte(serverConfig), 0600); err != nil {
			t.Fatal(err)
		}
		server := exec.Command(serverPath, "-config", serverConfigPath, "-nowait")
		server.Dir = directory
		server.Stdout, server.Stderr = io.Discard, io.Discard
		if err := server.Start(); err != nil {
			t.Fatal(err)
		}
		servers = append(servers, server)
		t.Cleanup(func() { server.Process.Kill(); server.Wait() })
		childAddress := reserveTCP()
		_, port, _ := net.SplitHostPort(childAddress)
		member := poolMemberConfig{ID: fmt.Sprintf("fixture-%d", i), Listen: childAddress, Username: "child", Password: "secret",
			ConfigPath: fmt.Sprintf("/proc/self/fd/%d", 4+i*2), ResolversPath: fmt.Sprintf("/proc/self/fd/%d", 5+i*2)}
		cfg.Members = append(cfg.Members, member)
		childConfig := fmt.Sprintf("DOMAINS = [\"%s\"]\nDATA_ENCRYPTION_METHOD = 5\nENCRYPTION_KEY = \"%s\"\n"+
			"PROTOCOL_TYPE = \"SOCKS5\"\nLISTEN_IP = \"127.0.0.1\"\nLISTEN_PORT = %s\n"+
			"SOCKS5_AUTH = true\nSOCKS5_USER = \"child\"\nSOCKS5_PASS = \"secret\"\nLOCAL_DNS_ENABLED = false\n"+
			"MIN_UPLOAD_MTU = 64\nMAX_UPLOAD_MTU = 64\nMIN_DOWNLOAD_MTU = 512\nMAX_DOWNLOAD_MTU = 512\n"+
			"MTU_TEST_TIMEOUT = 0.15\nMTU_TEST_RETRIES = 1\nMTU_TEST_PARALLELISM = 2\nLOG_LEVEL = \"ERROR\"\n"+
			"SAVE_MTU_SERVERS_TO_FILE = false\nRX_TX_WORKERS = 2\nARQ_WINDOW_SIZE = 32\n"+
			"UPLOAD_COMPRESSION_TYPE = 0\nDOWNLOAD_COMPRESSION_TYPE = 0\n", domain, key, port)
		files = append(files, poolTestMemfd(t, fmt.Sprintf("tuna-pool-fixture-config-%d", i), childConfig),
			poolTestMemfd(t, fmt.Sprintf("tuna-pool-fixture-resolvers-%d", i), fmt.Sprintf("127.0.0.1:%d\n", serverPort)))
	}
	raw, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	manifest := poolTestMemfd(t, "tuna-pool-fixture-manifest", string(raw))
	wrapper := exec.Command(clientPath, "-pool", "/proc/self/fd/3")
	wrapper.ExtraFiles = append([]*os.File{manifest}, files...)
	stdout, err := wrapper.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	wrapper.Stderr = io.Discard
	if err = wrapper.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { wrapper.Process.Kill(); wrapper.Wait() })
	lines := make(chan string, 512)
	go func() {
		defer close(lines)
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			lines <- scanner.Text()
		}
	}()
	waitReady := func(count int, duration time.Duration) {
		t.Helper()
		timer := time.NewTimer(duration)
		defer timer.Stop()
		for {
			select {
			case line, open := <-lines:
				if !open {
					t.Fatal("native pool exited before expected readiness")
				}
				if strings.Contains(line, fmt.Sprintf("\"ready\":%d,", count)) {
					return
				}
				if strings.Contains(line, "ERROR") {
					t.Fatal("native pool emitted global error")
				}
			case <-timer.C:
				t.Fatalf("native pool did not reach ready=%d", count)
			}
		}
	}
	waitReady(2, 35*time.Second)
	first := poolTestConnect(t, front)
	second := poolTestConnect(t, front)
	if fixtureExchange(t, first, 21) != 'A' || fixtureExchange(t, second, 22) != 'B' {
		t.Fatal("two independent native server streams not balanced")
	}
	children := poolNativeChildren(t, wrapper.Process.Pid)
	if len(children) != 2 {
		t.Fatalf("expected two independent client processes; got %v", children)
	}
	var victim int
	for _, pid := range children {
		secret, err := os.ReadFile(fmt.Sprintf("/proc/%d/fd/3", pid))
		if err != nil {
			t.Fatal(err)
		}
		if bytes.Contains(secret, []byte("tunnel-0.example.invalid")) {
			victim = pid
		}
		entries, err := os.ReadDir(fmt.Sprintf("/proc/%d/fd", pid))
		if err != nil {
			t.Fatal(err)
		}
		memberFDs := 0
		for _, entry := range entries {
			target, _ := os.Readlink(fmt.Sprintf("/proc/%d/fd/%s", pid, entry.Name()))
			if strings.Contains(target, "tuna-pool-fixture-") {
				memberFDs++
				if entry.Name() != "3" && entry.Name() != "4" {
					t.Fatal("live native child inherited another member descriptor")
				}
			}
		}
		if memberFDs != 2 {
			t.Fatal("live native child descriptor pair missing")
		}
	}
	if victim == 0 {
		t.Fatal("independent first client not found")
	}
	syscall.Kill(victim, syscall.SIGKILL)
	waitReady(1, 3*time.Second)
	if fixtureExchange(t, second, 23) != 'B' {
		t.Fatal("surviving native stream changed after other child crash")
	}
	third := poolTestConnect(t, front)
	if fixtureExchange(t, third, 24) != 'B' {
		t.Fatal("new native stream did not use surviving child")
	}
	waitReady(2, 35*time.Second)
	shutdownChildren := poolNativeChildren(t, wrapper.Process.Pid)
	shutdown := time.Now()
	wrapper.Process.Signal(syscall.SIGTERM)
	finished := make(chan error, 1)
	go func() { finished <- wrapper.Wait() }()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("native wrapper did not gracefully terminate in four seconds")
	}
	for _, pid := range shutdownChildren {
		if syscall.Kill(pid, 0) == nil {
			t.Fatal("native child survived graceful wrapper termination")
		}
	}
	t.Logf("two native servers, independent child secret FDs, RR, crash failover/restart, stream pinning; shutdown=%s", time.Since(shutdown))
}

func poolNativeChildren(t *testing.T, pid int) []int {
	t.Helper()
	result := poolNativeChildrenOptional(pid)
	if len(result) == 0 {
		t.Fatal("native children unavailable in procfs")
	}
	return result
}

func poolNativeChildrenOptional(pid int) []int {
	files, _ := filepath.Glob(fmt.Sprintf("/proc/%d/task/*/children", pid))
	seen := map[int]bool{}
	for _, file := range files {
		raw, _ := os.ReadFile(file)
		for _, value := range strings.Fields(string(raw)) {
			child, _ := strconv.Atoi(value)
			if child > 0 {
				seen[child] = true
			}
		}
	}
	// Android kernels commonly omit task/<tid>/children when checkpoint/
	// restore support is disabled. PPid is available independently of that
	// kernel option and refers to the process even for LockOSThread spawns.
	if len(seen) == 0 {
		entries, _ := os.ReadDir("/proc")
		deadline := time.Now().Add(2 * time.Second)
		for _, entry := range entries {
			if time.Now().After(deadline) {
				break
			}
			child, err := strconv.Atoi(entry.Name())
			if err != nil || child < 1 || !entry.IsDir() {
				continue
			}
			status, err := os.ReadFile("/proc/" + entry.Name() + "/status")
			if err != nil {
				continue
			}
			for _, line := range strings.Split(string(status), "\n") {
				if strings.HasPrefix(line, "PPid:") {
					parent, _ := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "PPid:")))
					if parent == pid {
						seen[child] = true
					}
					break
				}
			}
		}
	}
	result := make([]int, 0, len(seen))
	for child := range seen {
		result = append(result, child)
	}
	return result
}
