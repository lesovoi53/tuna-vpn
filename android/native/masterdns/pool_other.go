//go:build !linux && !android

package main

import (
	"errors"
	"os"
	"os/exec"
	"strings"
)

// Host platforms can run the SOCKS tests, but cannot run a private pool without
// Linux memfd and parent-death support. Fail closed instead of using disk files.
func poolPrivatePath(path string) bool       { return strings.HasPrefix(path, "/proc/self/fd/") }
func poolSealInheritedFiles() error          { return errors.New("private pools require Linux or Android") }
func poolChildAttributes(cmd *exec.Cmd)      {}
func poolTerminateChild(process *os.Process) { process.Kill() }
