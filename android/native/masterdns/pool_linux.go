//go:build linux || android

package main

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

func poolPrivatePath(path string) bool {
	const prefix = "/proc/self/fd/"
	if !strings.HasPrefix(path, prefix) {
		info, err := os.Lstat(path)
		if err != nil || !filepath.IsAbs(path) || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
			return false
		}
		stat, ok := info.Sys().(*syscall.Stat_t)
		return ok && stat.Uid == uint32(os.Geteuid())
	}
	number, err := strconv.Atoi(strings.TrimPrefix(path, prefix))
	return err == nil && number >= 3 && path == prefix+strconv.Itoa(number)
}

func poolSealInheritedFiles() error {
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return errors.New("cannot isolate child descriptors")
	}
	for _, entry := range entries {
		fd, err := strconv.Atoi(entry.Name())
		if err != nil || fd < 3 {
			continue
		}
		// ReadDir may already have closed its own enumeration descriptor.
		_, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), syscall.F_SETFD, syscall.FD_CLOEXEC)
		if errno != 0 && errno != syscall.EBADF {
			return errors.New("cannot isolate child descriptors")
		}
	}
	return nil
}

func poolChildAttributes(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
}

func poolTerminateChild(process *os.Process) {
	process.Signal(syscall.SIGTERM)
}
