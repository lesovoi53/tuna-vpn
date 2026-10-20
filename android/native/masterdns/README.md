# MasterDnsVPN Android adapter

Upstream: masterking32/MasterDnsVPN, 7de2476f1c33e69eec35360c52810dab43c5c986, MIT.

`build.ps1 -GoExe <go.exe> -PythonExe <python.exe>` builds Android arm64 with CGO disabled, following the upstream Android arm64 target. `main.go` runs the upstream config loader and client; `-scan` runs the native resolver/MTU scanner without opening a VPN or SOCKS listener. `-validate` parses the private configuration without network activity. Scanner emits JSON as each domain/resolver pair completes; it stops after 3 minutes or process cancellation. A timeout leaves explicitly partial results.

`prepare.py` creates a Go overlay adding stdout lifecycle markers after local listener startup and on session reset. These markers are independent of user LOG_LEVEL. It appends `scan.go.inc` to the upstream MTU implementation and adds its host tests through the Go overlay. No DNS wire-format code is modified. Upstream source remains pinned; overlay artifacts are under build/.

The scan adapter uses eight workers and a 25-second per-pair limit. It calls the
original MTU discovery and then sends five original download MTU probes at the
discovered upload/download sizes. Upstream validation checks response type, fresh
probe code and payload size. `attempts`/`successes` describe only these five probes;
`latencyMs` is their successful median RTT and `mtuCheckMs` is elapsed discovery
time. Zero attempts means no repeated measurement. A regular DNS answer never
proves tunnel compatibility. No application traffic or throughput test is run.

Android supplies loopback resolver endpoints backed by a temporary UDP relay.
Every remote relay socket is explicitly bound to the user's selected physical
Wi-Fi/cellular `Network`; the process and app default network remain untouched.
Connected remote sockets enforce the source endpoint; the relay rewrites DNS IDs,
correlates requests, checks response opcode/question and rejects truncation.
Closing/cancelling the scan closes its selector, sockets and child process.
The scan adapter reloads the explicit resolver file with IP-and-port identity
before bootstrap. Upstream's normal loader deduplicates by IP, which would collapse
all loopback relay ports into one candidate. This correction is restricted to
`-scan` and the explicit `-diagnostic` isolated runtime; ordinary runtime resolver parsing is unchanged. Input remains limited
to 128 literal IP endpoints and never expands hostnames or CIDRs.

For catalogues larger than 128, Kotlin first sends randomized TXT questions under
the configured tunnel domain with at most 64 concurrent probes and 100 scheduled
queries/second. This reachability hint does not declare tunnel compatibility.
Up to 32 configured resolvers are reserved in the final list even if ordinary DNS
timed out; remaining slots prefer validated replies, then sample nonresponders. The
native shortlist is at most 128 endpoints. The fast pass accepts up to 100,000
catalogue addresses and has a 20-minute bound; omitted endpoints remain untested.
Native scan-only timeout/retry caps are temporary and do not change profile settings.

`-resolvers <path>` passes the resolver filename through upstream
`LoadClientConfigWithOverrides` before its validation reads the file. This supports
an absolute `/proc/self/fd/N` from the memory launcher. The upstream field
`ResolversFilePath` is marked `toml:"-"`, so placing a resolver path in TOML does
not replace this flag. Without the flag, the original adjacent
`client_resolvers.txt` default is preserved.

When the pinned checkout is unavailable, build with `-Archive <cached tar.gz>`.
The script verifies SHA-256
`432e2b8b496423da18c80654e3de36fc3646b068d1139b237d6fec540b5162d1`,
extracts only its expected commit root into a new ignored build directory, runs
upstream config/client tests, then builds arm64. The archive hash is recorded in
provenance; no upstream source is patched in place.

The application owns loopback listening address, SOCKS credentials, working directory and process lifetime. Existing TUNA fake-DNS handles application DNS; the additional upstream unauthenticated local DNS listener stays disabled. User-facing settings cover the 71 non-managed fields in the pinned defaultClientConfig plus domain/key/encryption/resolvers. TFO, MPTCP and other sing-box settings are excluded.

Tests: Kotlin round-trip/all-field serialization/group exclusion, upstream config/SOCKS/MTU tests, and native `-validate` of the Kotlin-produced non-secret fixture. Real resolver reachability and Android VPN traffic remain manual acceptance tests. No claim of wire compatibility with an uninspected live server version.

`tests/scan_loopback.py --scanner <host scan exe> --server <host upstream server exe>
--output <ignored build directory>` tests genuine local protocol replies, two lost
samples (3/5), an incorrect AES key and ordinary DNS replies, plus distinct results
for two and 128 resolver ports on the same IP. Both server and proxy
bind only loopback, use a synthetic domain/key and stop in `finally`. These checks
do not establish Android network binding or public resolver acceptance.

`-diagnostic` opens a complete temporary tunnel through Android's network-bound
loopback relays. The flag requires authenticated SOCKS, a loopback listener and
loopback resolver endpoints; it never changes the active VPN. Its safe
`TUNA_MASTER_POLICY` JSON contains only an allow-list of numeric effective settings.
The overlay emits it after server policy clamping and at listener readiness, so
the app can show actual workers/window/duplication rather than assume requested
settings were accepted. Normal runtime does not emit this policy JSON.

`MasterDnsGoodput` requests one HTTPS body through that authenticated SOCKS
connection. It sends the destination as a SOCKS hostname, verifies the platform
TLS chain and HTTPS hostname, rejects redirects/compressed or ambiguous HTTP
framing, and limits each run to 256 KiB application payload, 180 seconds by default
and 8 MiB accounted DNS UDP payload in both directions. It leaves 65,535 bytes
under that UDP cap for the last received datagram. IP/UDP/link overhead and packets
already in flight after socket closure are outside this accounting. On failure,
network loss or cancellation it closes all sockets/relays and destroys the child
process. Only complete responses of at least 16 KiB are accepted for comparison.
The elapsed request time excludes native/SOCKS/TLS setup but includes response
wait and headers (avoiding inflation from TLS-buffered body bytes); rates belong to
the complete tested pool and are not per-resolver measurements.

`tests/diagnostic_loopback.py --client <host adapter exe> --server <host server exe>
--output <ignored build directory>` establishes a real native session where the
first loopback resolver port is silent and the second works. It checks server
policy clamping, rejection of absent/wrong SOCKS authentication and byte-for-byte
delivery of a synthetic 32 KiB stream. Its synthetic upstream SOCKS fixture does
not resolve or dial any public target. `MasterDnsGoodputTest` separately exercises
real authenticated SOCKS and TLS with scoped fixture trust, endpoint identity,
body bounds, redirects, truncation, timeout and cancellation. These local checks
do not establish physical Android Network binding or public-resolver throughput.

`-pool /proc/self/fd/N` runs a separate supervisor with 2–8 independent copies of
the same native executable. Version 1 JSON contains `version`, authenticated
loopback `listen`/`username`/`password`, `strategy` (`round_robin` or
`least_connections`), and `members`. Each member supplies a unique opaque `id`,
`configPath`, `resolversPath`, its own authenticated loopback `listen`/credentials.
All JSON/TOML/resolver inputs are inherited memory descriptors. Ports and input
descriptor paths must be unique; child TOML must match its declared endpoint and
credentials and disable local DNS and MTU-file output. `-pool -validate` checks
the complete contract without starting listeners or children.

Before any child starts, the supervisor marks all inherited descriptors above
stderr close-on-exec. Only that member's TOML/resolver pair is passed as FD 3/4.
No pool JSON or other member's descriptors survive child exec. Each child has its
own upstream client, key, domain, resolver set and session. Child logs are
discarded; only aggregate READY/CONNECTING and numeric `TUNA_MASTER_POOL` status
with opaque IDs reach stdout. A healthy child keeps the aggregate READY when
another child fails. Restarts back off from 250 ms to 15 seconds; Linux/Android
parent-death SIGKILL and a retained spawning OS thread prevent orphaned clients.
Normal cancellation closes streams and listeners, signals children concurrently,
then force-kills after two seconds and reaps them.

New authenticated CONNECT requests select only ready children. Round robin and
least active connections reserve a slot before dialing; established streams
never move or replay. A failed child handshake can retry another member before
application bytes are read. A valid destination refusal preserves its SOCKS REP
without marking the entire VPS unhealthy; transport/authentication failures
temporarily cool that member for two seconds. The frontend limits concurrent
connections to 256, setup to 15 seconds and each child handshake to eight seconds.
Hostnames stay in SOCKS requests and are never resolved locally.

UDP ASSOCIATE preserves upstream's DNS/53-only behavior. Each association is
pinned to one authenticated child and relayed through loopback sockets. The
wrapper declares its exact child-facing UDP source port during authentication;
both relay hops verify source endpoints. Fragments, non-DNS destinations,
unsolicited DNS IDs/questions and malformed packets are discarded. At most 256
queries are pending per association, with 30-second correlation and a 60-second
UDP inactivity bound. Closing either TCP control channel closes both UDP
sockets. A narrowly gated `-pool-child` overlay binds the upstream UDP listener
to loopback and ties its lifetime to the control connection/runtime. General UDP
and direct fallback are not provided. Existing standalone/scan/diagnostic modes
retain their behavior.

Host tests cover selection, auth, destination failure, pinned streams, cleanup
and DNS-only UDP filtering. Linux/Android tests additionally prove inherited-FD
isolation and child death after abrupt wrapper SIGKILL. The opt-in
`TestPoolTwoIndependentNativeServers` uses `TUNA_POOL_NATIVE_CLIENT` and
`TUNA_POOL_NATIVE_SERVER` paths to run two genuine loopback upstream servers with
synthetic keys, check independent child descriptors, stream balancing, restart,
survivor behavior and graceful shutdown. Set `TMPDIR` to an isolated writable
test directory on Android. This fixture never uses production profiles or
resolvers; it does not prove physical Android VPN/network binding.
