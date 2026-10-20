# qWDTT private configuration overlay

Upstream: SpaceNeuroX/proxy-turn-vk-android, commit
`a296c57eaba69bb9479a24f9157856490890e47d` (the existing pinned submodule).
The reviewed archive SHA-256 is enforced by `prepare.py`. The source is restored
under `build/qwdtt-overlay/source`; a Go overlay changes only client launch and
adds the private configuration parser. `go.mod` retains upstream dependency
versions; `go.sum` records verified module checksums missing from the archive.
Upstream licensing and attribution remain in the repository's submodule.

`-config /proc/self/fd/N` reads a bounded JSON object containing only `vk`,
`password`, `socks-user`, and `socks-pass`. These four values must not also appear
as CLI flags. Other existing flags remain unchanged. stdin remains open for
PAUSE/RESUME/STOP/CAPTCHA_RESULT/TURN_CREDS. Its contents are no longer echoed.
The SOCKS mode used by Android does not print or save the server-provided
WireGuard configuration. Legacy non-SOCKS export behavior remains unchanged.

Browser fingerprint files (`vk_profile.json`, `captcha_browser_fp`) remain;
they hold browser/device identity, not tunnel passwords or WireGuard keys.

Build with `build.ps1 -GoExe ... -PythonExe ... -Archive ... -AndroidSdk ...`.
The archive can be obtained from the pinned GitHub commit archive. The build
runs parser regression tests on Windows, then builds the complete Android
arm64/API 26 client using NDK 28.2.13676358. Upstream has no Go tests and its
Unix TUN source cannot compile as a Windows program. The linker option
`-checklinkname=0` is required by upstream `github.com/wlynxg/anet`.
Output and provenance are under `build/native-cache`; copy both when packaging.
Android execution and real-server behavior require separate acceptance.
