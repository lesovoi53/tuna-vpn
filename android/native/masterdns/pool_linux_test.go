//go:build linux || android

package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func poolTestMemfd(t *testing.T, name, content string) *os.File {
	t.Helper()
	fd, err := unix.MemfdCreate(name, unix.MFD_CLOEXEC)
	if err != nil {
		t.Fatal(err)
	}
	file := os.NewFile(uintptr(fd), name)
	t.Cleanup(func() { file.Close() })
	if _, err = file.WriteString(content); err != nil {
		t.Fatal(err)
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	return file
}

func TestPoolProcessHelper(t *testing.T) {
	mode := os.Getenv("TUNA_POOL_TEST_HELPER")
	switch mode {
	case "":
		return
	case "fds":
		entries, err := os.ReadDir("/proc/self/fd")
		if err != nil {
			os.Exit(10)
		}
		names := []string{}
		for _, entry := range entries {
			target, _ := os.Readlink("/proc/self/fd/" + entry.Name())
			if strings.Contains(target, "tuna-pool-fixture-") {
				names = append(names, entry.Name()+":"+target)
			}
		}
		json.NewEncoder(os.Stdout).Encode(names)
		os.Exit(0)
	case "parent":
		runtime.LockOSThread()
		child := exec.Command(os.Args[0], "-test.run=^TestPoolProcessHelper$")
		child.Env = append(os.Environ(), "TUNA_POOL_TEST_HELPER=child")
		poolChildAttributes(child)
		if child.Start() != nil {
			os.Exit(11)
		}
		fmt.Println(child.Process.Pid)
		child.Wait()
		os.Exit(0)
	case "child":
		// Remain in a syscall rather than triggering the Go all-goroutines
		// asleep detector. Parent termination must cause SIGKILL.
		for {
			time.Sleep(time.Hour)
		}
	}
}

func TestPoolChildInheritsOnlyItsOwnPair(t *testing.T) {
	ownConfig := poolTestMemfd(t, "tuna-pool-fixture-own-config", "synthetic config")
	ownResolvers := poolTestMemfd(t, "tuna-pool-fixture-own-resolvers", "127.0.0.1:53\n")
	other := poolTestMemfd(t, "tuna-pool-fixture-other-secret", "must never be inherited")
	poolJSON := poolTestMemfd(t, "tuna-pool-fixture-pool-json", "contains every member")
	for _, file := range []*os.File{ownConfig, ownResolvers, other, poolJSON} {
		if _, err := unix.FcntlInt(file.Fd(), unix.F_SETFD, 0); err != nil {
			t.Fatal(err)
		}
	}
	if err := poolSealInheritedFiles(); err != nil {
		t.Fatal(err)
	}
	for _, file := range []*os.File{ownConfig, ownResolvers, other, poolJSON} {
		flags, err := unix.FcntlInt(file.Fd(), unix.F_GETFD, 0)
		if err != nil || flags&unix.FD_CLOEXEC == 0 {
			t.Fatal("incoming config descriptor was not sealed")
		}
	}
	child := exec.Command(os.Args[0], "-test.run=^TestPoolProcessHelper$")
	child.Env = append(os.Environ(), "TUNA_POOL_TEST_HELPER=fds")
	child.ExtraFiles = []*os.File{ownConfig, ownResolvers}
	output, err := child.CombinedOutput()
	if err != nil {
		t.Fatalf("FD helper: %v %s", err, output)
	}
	var inherited []string
	if json.Unmarshal(bytes.TrimSpace(output), &inherited) != nil || len(inherited) != 2 ||
		!strings.HasPrefix(inherited[0], "3:/memfd:tuna-pool-fixture-own-config") ||
		!strings.HasPrefix(inherited[1], "4:/memfd:tuna-pool-fixture-own-resolvers") {
		t.Fatalf("child inherited other-member secrets: %q", inherited)
	}
}

func TestPoolAbruptParentDeathKillsChild(t *testing.T) {
	parent := exec.Command(os.Args[0], "-test.run=^TestPoolProcessHelper$")
	parent.Env = append(os.Environ(), "TUNA_POOL_TEST_HELPER=parent")
	stdout, err := parent.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = parent.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { parent.Process.Kill(); parent.Wait() }()
	pidLine := make(chan string, 1)
	go func() {
		line, _ := bufio.NewReader(stdout).ReadString('\n')
		pidLine <- strings.TrimSpace(line)
	}()
	var pid int
	select {
	case line := <-pidLine:
		pid, err = strconv.Atoi(line)
		if err != nil || pid < 1 {
			t.Fatalf("child pid unavailable: %q", line)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("child startup timed out")
	}
	defer syscall.Kill(pid, syscall.SIGKILL)
	parent.Process.Kill()
	parent.Wait()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		status, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
		if os.IsNotExist(err) || bytes.Contains(status, []byte("State:\tZ")) {
			return // A zombie is already dead; init/Android will reap it.
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("child remained alive after abrupt wrapper SIGKILL")
}
