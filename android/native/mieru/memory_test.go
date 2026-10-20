// SPDX-License-Identifier: GPL-3.0-or-later
package appctl

import (
	pb "github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"google.golang.org/protobuf/proto"
	"testing"
)

func TestTunaMemoryConfigSnapshot(t *testing.T) {
	config := &pb.ClientConfig{Socks5Port: proto.Int32(2081)}
	clientIOLock.Lock()
	tunaMemoryConfig = proto.Clone(config).(*pb.ClientConfig)
	clientIOLock.Unlock()
	t.Cleanup(func() { clientIOLock.Lock(); tunaMemoryConfig = nil; clientIOLock.Unlock() })
	// An invalid disk path proves loads/stores take the memory branch.
	t.Setenv(EnvMieruConfigJSONFile, "nonexistent/no-config.json")
	first, err := LoadClientConfig()
	if err != nil {
		t.Fatal(err)
	}
	first.Socks5Port = proto.Int32(9999)
	second, err := LoadClientConfig()
	if err != nil || second.GetSocks5Port() != 2081 {
		t.Fatal("snapshot was mutated", err)
	}
	if StoreClientConfig(first) == nil {
		t.Fatal("memory snapshot allowed persistent mutation")
	}
}
