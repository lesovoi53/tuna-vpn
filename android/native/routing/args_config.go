package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"os"
	"strings"
)

func tunaParseArgs(flags *flag.FlagSet, args []string) error {
	if len(args) == 0 || args[0] != "-args-config" {
		return flags.Parse(args)
	}
	rejected := errors.New("private launch configuration rejected")
	if len(args) != 2 {
		return rejected
	}
	file, err := os.Open(args[1])
	if err != nil {
		return rejected
	}
	defer file.Close()
	const limit = 4 * 1024 * 1024
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil || len(data) > limit {
		return rejected
	}
	var privateArgs []string
	decoder := json.NewDecoder(bytes.NewReader(data))
	if decoder.Decode(&privateArgs) != nil || len(privateArgs) == 0 || len(privateArgs) > 2048 {
		return rejected
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return rejected
	}
	for _, arg := range privateArgs {
		if strings.IndexByte(arg, 0) >= 0 || arg == "-args-config" || strings.HasPrefix(arg, "-args-config=") {
			return rejected
		}
	}
	// flag's default errors include invalid values. Keep private argument values
	// out of diagnostics, and retain the old parser for legacy CLI invocations.
	output := flags.Output()
	handling := flags.ErrorHandling()
	flags.Init(flags.Name(), flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	defer func() { flags.SetOutput(output); flags.Init(flags.Name(), handling) }()
	if flags.Parse(privateArgs) != nil || flags.NArg() != 0 {
		return rejected
	}
	return nil
}
