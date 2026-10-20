//go:build linux || android

package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPoolOwnerOnlyRuntimeFiles(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "config.json")
	if err := os.WriteFile(file, []byte("{}"), 0600); err != nil {
		t.Fatal(err)
	}
	if !poolPrivatePath(file) {
		t.Fatal("owner-only runtime file rejected")
	}
	if poolPrivatePath(root) {
		t.Fatal("directory accepted")
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(file, link); err != nil {
		t.Fatal(err)
	}
	if poolPrivatePath(link) {
		t.Fatal("symlink accepted")
	}
	if err := os.Chmod(file, 0644); err != nil {
		t.Fatal(err)
	}
	if poolPrivatePath(file) {
		t.Fatal("world-readable file accepted")
	}
	if poolPrivatePath(filepath.Join(root, "missing")) {
		t.Fatal("missing file accepted")
	}
}
