# MasterDnsVPN Android adapter

Upstream: masterking32/MasterDnsVPN, 7de2476f1c33e69eec35360c52810dab43c5c986, MIT.

`build.ps1 -GoExe <go.exe> -PythonExe <python.exe>` builds Android arm64 with CGO disabled, following the upstream Android arm64 target. `main.go` runs the upstream config loader and client; `-scan` runs the native resolver/MTU scanner without opening a VPN or SOCKS listener. `-validate` parses the private configuration without network activity. Scanner emits JSON per domain/resolver pair after completion and upstream per-pair progress while working; it stops after 3 minutes or process cancellation.

`prepare.py` creates a Go overlay adding stdout lifecycle markers after local listener startup and on session reset. These markers are independent of user LOG_LEVEL. No DNS wire-format code is modified. Upstream source remains pinned; overlay artifacts are under build/.

The application owns loopback listening address, SOCKS credentials, working directory and process lifetime. Existing TUNA fake-DNS handles application DNS; the additional upstream unauthenticated local DNS listener stays disabled. User-facing settings cover the 71 non-managed fields in the pinned defaultClientConfig plus domain/key/encryption/resolvers. TFO, MPTCP and other sing-box settings are excluded.

Tests: Kotlin round-trip/all-field serialization/group exclusion, upstream config/SOCKS/MTU tests, and native `-validate` of the Kotlin-produced non-secret fixture. Real resolver reachability and Android VPN traffic remain manual acceptance tests. No claim of wire compatibility with an uninspected live server version.
