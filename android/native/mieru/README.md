# Mieru adapter and authentication overlay

The stable upstream release is pinned in `../../native-lock.json`. The build
requires a clean checkout at that exact commit and checks that Git resolves the
Mieru checkout itself, rather than silently using a parent repository.

The TUNA launcher retains strict URI validation, a loopback SOCKS listener and
required local credentials. `--version` reports the linked upstream version
without reading a launch file. Both host and Android builds use the same source
and authentication overlay.

Mieru 3.38.1 includes the upstream fix preventing authentication downgrade.
`auth.go` is rebased onto that release and additionally sends SOCKS response
`05 ff` when a client cannot supply the required credentials. This preserves the
existing TUNA denial response. All upstream authentication tests run; the one
case expecting EOF is adapted to require this explicit rejection. The separate
TUNA regression still checks no-auth denial, wrong credentials and successful
authentication when both methods are offered.

`build.ps1` runs adapter and SOCKS package tests, builds and executes the host
version check, then cross-compiles Android arm64/API 26 using NDK 28.2.13676358.
It produces `build/native-cache/libmieru.so` and matching source-hash provenance.
The main build script copies these to the packaged JNI library and
`native/mieru/provenance.json`; regenerate the aggregate core manifest afterward.

Host tests and cross-compilation do not establish Android device or real-server
acceptance. The upstream SOCKS authentication tests remain in the clean submodule;
the expected-response adaptation is applied only through the generated overlay.
