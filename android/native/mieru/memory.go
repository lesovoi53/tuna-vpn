// SPDX-License-Identifier: GPL-3.0-or-later
package appctl

import (
	"fmt"
	pb "github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"google.golang.org/protobuf/proto"
)

// The adapter owns one immutable launch snapshot for this native process.
// Repeated CLI loads return clones, so callers cannot mutate the snapshot.
var tunaMemoryConfig *pb.ClientConfig

func SetTunaMemoryConfig(config *pb.ClientConfig) error {
	if config == nil {
		return fmt.Errorf("missing memory configuration")
	}
	if err := ValidateFullClientConfig(config); err != nil {
		return err
	}
	clientIOLock.Lock()
	defer clientIOLock.Unlock()
	if tunaMemoryConfig != nil {
		return fmt.Errorf("memory configuration already set")
	}
	tunaMemoryConfig = proto.Clone(config).(*pb.ClientConfig)
	return nil
}
