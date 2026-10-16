# TUNA VPN

An Android VPN client built around Xray, sing-box-extended and Mieru, with a standalone Windows routing editor. [Russian documentation](README.md) is the primary maintained guide.

[Download release 1.5](https://github.com/lesovoi53/tuna-vpn/releases/tag/v1.5) · [Build](docs/development/build.md) · [Licenses](docs/licenses.md)

## Included

- Android 8.0+ ARM64 APK, version 1.5.
- TUNA Routing Builder 2.3.1, a single-file Windows EXE and Python source.
- Per-app/domain/IP routing, subscription rules, URL-test groups and separate direct/VPN DNS pools (up to eight each).
- Source, native patch overlays, pinned dependencies, tests and developer documentation.

Bring your own servers/subscription. No private service credentials are distributed. The Windows editor is not a VPN client. Only trust subscriptions whose rules you accept: routing rules can apply automatically.

The compatibility APK is a non-debuggable release build, but retains the historical `app.tunaturn.android.debug` application ID and Android Debug signing certificate. The EXE is not Authenticode-signed. See [release verification](docs/development/release.md) before installing. No fresh full-device acceptance or bit-for-bit reproducible build is implied by a successful compile.

Clone with `git clone --recurse-submodules https://github.com/lesovoi53/tuna-vpn.git`. GitHub's default source ZIP excludes submodule contents. Native source modifications live under `android/native/`; wrappers live under `android/external/`. Some native components are extracted from a checksum-pinned upstream APK; see the explicit provenance limitations.

Derived from WireTurn. TUNA patches do not claim authorship of upstream cores. Project code is GPL-3.0 unless a file declares another license; third-party licenses and credits are retained. [Core forks](docs/development/cores-and-forks.md) · [Security reporting](SECURITY.md).

