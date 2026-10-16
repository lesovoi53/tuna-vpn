# Snell UDP regression fixture

`snell_udp_test.go` runs a real local Snell v4 client / v5 server from the pinned
dependency. It does not use credentials or contact a remote server. The FQDN echo
resolves only inside the fixture to TEST-NET, and the IPv6 destination is also
documentation-only. Test coverage: none/HTTP/TLS obfuscation, record size limits,
parallel writes, address/payload integrity, and continued use after rejecting an
oversized datagram. The warm-up reflects the dependency's adaptive record ceiling.

Run from `external/sing-box-extended`, with the module and dependency overlay
prepared by `native/singbox/build.ps1`:

```powershell
go test -mod=readonly -modfile=../../build/native-cache/singbox.mod -overlay=../../build/native-cache/go-overlay.json -count=3 -timeout=90s ../../tests/synthetic-rc30/snell_udp_test.go
```

For the Android check compile this same file with the ARM64/NDK environment from
`native/singbox/build.ps1` and `go test -c -o <temporary-test-binary>`, then run it
through the authorized device's ADB shell. Remove the temporary binary afterward.
Linux `-race` requires Linux-compatible module/overlay paths; the Windows JSON
paths cannot be reused verbatim in WSL. No production source change is required.

Acceptance on 2026-10-03: Linux race 3 repeats (1350 packet exchanges); HONOR ARM64
10 repeats (4500 packet exchanges). This is a local protocol regression check,
not proof of connectivity to any particular Snell server.
