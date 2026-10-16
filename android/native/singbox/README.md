# Pinned extended build with uTLS override

The upstream sing-box-extended submodule remains clean at the commit in `native-lock.json`.
`utls.mod` and `utls.sum` are alternate Go module inputs based on that exact source.
They replace only `github.com/metacubex/utls` with the pinned commit from
`v1.9.0-mod-meta`; this branch is not a tagged stable release.

Build with `native/singbox/build.ps1`. Both host validation and Android builds use
`-mod=readonly -modfile=...`; the build checks the replacement against the lock.
The Android binary's Go build information is checked before packaging. The asset
manifest records the uTLS revision and module input hashes. Gradle verifies them
before accepting the prebuilt binary.

The original `v1.8.7` requirement remains in the alternate module file. The explicit
replacement selects the pinned pseudo-version even when another dependency requires
v1.8.7. Updating only the direct requirement to the lower `v0.0.0-...` pseudo-version
would not reliably select this branch under Go's minimum version selection.

The updated library exposes Firefox 148 and Safari 26.3 with hybrid key shares.
This does not itself enable `tls.reality.support_x25519mlkem768` in profiles and
does not prove compatibility with a specific Reality server. Original profiles and
explicit fingerprints are preserved; test both legacy and hybrid modes separately.

`source-overlay.json` adds the Reality fallback request path (`spider_x`) to the
pinned source via Go's build overlay. Every replacement must match exactly once;
the upstream submodule is never edited. The overlay hash is part of the native
manifest and is verified before packaging. Only a local path is supported: Xray's
optional crawler query controls are rejected instead of silently discarded.
Reality authentication and certificate verification are unchanged.
