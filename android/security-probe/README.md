# Standalone Android security acceptance tools

These Java APKs use SDK tools directly and have no app or AndroidX runtime
dependency. They do not replace the production app's tests. Build outputs are
ignored; private signing material must remain outside this public source tree.

## Build

```powershell
./build.ps1 -AndroidSdk '<sdk-root>' -JavaHome '<jdk-root>'
./build.ps1 -AndroidSdk '<sdk-root>' -JavaHome '<jdk-root>' -AcceptanceOnly
./build.ps1 -AndroidSdk '<sdk-root>' -JavaHome '<jdk-root>' -ProbeOnly
```

The pinned default toolchain is Build Tools 37.0.0, platform android-37.0, minSdk 26
and targetSdk 37. The outputs in `build/` are:

* `SecurityProbe.apk`: ordinary app `app.tuna.securityprobe`, signed by a separate EC
  test certificate. No shared UID, instrumentation or privileged permission. Its
  permissions are INTERNET and ACCESS_NETWORK_STATE; visibility names one target.
* `SecurityContinuity-unsigned.apk`: aligned instrumentation targeting
  `app.tunaturn.android.debug`. The owner must sign it with the installed target's
  trusted signer. The build script never uses a target signing key.
* `SecurityAcceptance-unsigned.apk`: separate aligned instrumentation for the
  installed resolver bridge, verifier and optional native emergency path. Build
  with `-AcceptanceOnly` without changing the probe or continuity artifacts.

`artifacts.json` records the probe certificate and APK hashes. The build verifies
the ordinary probe's v2/v3 signature and rejects the historical TUNA debug signer.
The generated 30-day test key is encrypted under the workspace's
`.scratch/tuna-security-probe-signing/reusable-ephemeral`, outside this public
checkout. Its random password is encrypted with Windows current-user DPAPI in
the same directory and only decrypted into the build process environment during
signing. Later local builds reuse that test signer for `install -r`. The original
one-shot probe build discarded its password; replacing that earliest build needs
owner-authorized removal/reinstallation of only `app.tuna.securityprobe`.
DPAPI requires the Windows user's loaded profile; a restricted sandbox token may
fail with `Cryptography_DpApi_ProfileMayNotBeLoaded`. That is a build failure, not
a reason to store the password in plaintext or silently replace an existing key.

`test.ps1` tests the independent preference decoder using cached AndroidX
`datastore-preferences-proto` and `datastore-preferences-external-protobuf` JARs
supplied by the caller. Those libraries are host fixtures only and are not packaged.
The tests cover all eight preference value types, set order/deduplication,
plaintext-to-AES migration equality, changed keys/values, wrong AAD, unsupported
version, truncated input and malformed varints. Windows filesystem sandboxing can
prevent javac from reading classpath directories; an AccessDenied/compiler error
is a failed run even if that compiler incorrectly returns zero.

## Ordinary-UID broadcast and DNS probe

Run only against an authorized test device, after recording the target's state.
The probe can change that state if the target is vulnerable; this is the purpose
of the control-broadcast test. It sends no real profile IDs or credentials.

```text
adb install -r SecurityProbe.apk
adb shell am start -W -n app.tuna.securityprobe/app.tuna.securityprobe.ProbeActivity -a app.tuna.securityprobe.RUN --es mode inspect
adb shell am start -W -n app.tuna.securityprobe/app.tuna.securityprobe.ProbeActivity -a app.tuna.securityprobe.RUN --es mode broadcasts
adb shell am start -W -n app.tuna.securityprobe/app.tuna.securityprobe.ProbeActivity -a app.tuna.securityprobe.RUN --es mode dns --ei count 4
adb shell am start -W -n app.tuna.securityprobe/app.tuna.securityprobe.ProbeActivity -a app.tuna.securityprobe.RUN --es mode https
adb logcat -d -s TUNA_SECURITY_PROBE:I
```

The package and receiver are fixed: `app.tunaturn.android.debug` /
`com.wireturn.app.CoreReceiver`. Logs contain the ordinary caller UID, target UID,
current signer fingerprints, target version, receiver exported/enabled/permission
metadata and ten results: START_CORE, STOP_CORE, START_VPN, STOP_VPN and
CHANGE_PROFILE via explicit component and package-scoped implicit intent.
The caller refuses the run if it shares the target UID or current signer.

Android may silently drop an unauthorized broadcast. `submitted_not_delivery_proof`
only means `sendBroadcast` returned. Correlate the runId/timing with target state
and Android permission-denial logs. A package-scoped intent may also have no
matching filter. The inspected current CoreReceiver has no CHANGE_PROFILE branch;
that attempt cannot prove a profile-change handler is protected. No before/after
profile-state comparison is claimed by the ordinary app.

DNS mode requires API 29+. It performs 1–8 bounded, sequential A/AAAA queries for
the fixed public domain `example.com` on the default network, with Android resolver
cache lookup/storage disabled. It logs durations, response codes and counts, never
IP addresses. It neither forces a physical network nor disables Private DNS or
VPN. Each question records capabilities of the app's effective default network
before and after, whether it is VPN, whether the process is explicitly bound, the
separate caller/target UIDs, and network owner UID when Android exposes it. No
addresses, interfaces, network IDs or SSIDs are logged. `transportVpn=true`,
`ownerMatchesTarget=true`, `processNetworkBound=false`, stable network and a
successful query together support that this ordinary UID used TUNA's VPN default
network. If owner UID is hidden, correlate with the owner-recorded VPN state;
capabilities do not identify a particular core, DNS upstream or packet path.

HTTPS mode issues a HEAD request to fixed `https://example.com/` with normal TLS
verification, no redirects, no cookies set by the probe, no response body logging,
5-second connect/read limits and an 8-second observation deadline. It records only
response code, exception class, elapsed time and the same network snapshots.
The single-worker executor bounds abandoned work if framework DNS does not stop
on interruption. Starting a new action cancels the previous observation. Activity
`singleTop` delivers changed command extras when it is already foreground; verify
the new `runId` instead of assuming that a successful `am start` ran the test.

## VPN MTU and bounded HTTPS transfer

`mode mtu` emits one network snapshot. All DNS and HTTPS snapshots also include
`linkPropertiesAvailable`, `mtuAvailable` and `effectiveMtu` from the effective
network's `LinkProperties.getMtu()`. A missing value or Android's zero MTU is
reported as unavailable/null, never guessed from the selected setting. No
interface name, network address, profile or credential is read or logged.

```text
adb shell am start -W -n app.tuna.securityprobe/app.tuna.securityprobe.ProbeActivity -a app.tuna.securityprobe.RUN --es mode mtu
adb shell am start -W -n app.tuna.securityprobe/app.tuna.securityprobe.ProbeActivity -a app.tuna.securityprobe.RUN --es mode dns --ei count 4
adb shell am start -W -n app.tuna.securityprobe/app.tuna.securityprobe.ProbeActivity -a app.tuna.securityprobe.RUN --es mode https-body
```

After each authorized app setting change (1280, 1500, 9000, and the app's maximum),
reconnect the VPN and capture a fresh runId. Require `effectiveMtu` equal to that
setting, `transportVpn=true`, independent UID, no process binding, and stable
before/after networks. Record DNS A/AAAA outcomes and transfer outcomes separately.
MTU shown by a physical/default network without VPN is not TUNA's effective MTU.
Owner UID may be hidden by Android; correlate with the owner's recorded VPN state.

`https-body` uses fixed `https://speed.cloudflare.com/__down?bytes=262144`, a GET
with normal TLS verification, redirects disabled and `Accept-Encoding: identity`.
It reads at most 262144 response bytes with an 8192-byte buffer and discards them.
Only counts/status are logged. Connect/read limits are 5 seconds, overall
observation deadline 20 seconds. `boundedTransferSucceeded=true` requires HTTP
200, no exception, and all 262144 bytes. Error responses are also bounded and
counted, but do not pass. `bodyLimitReached=true` means the read cap was reached;
it does not claim EOF. Smaller/blocked responses do not prove a large transfer.
The original `https` HEAD mode remains available unchanged.

This checks Android's advertised VPN MTU and a multi-packet HTTPS transfer through
the effective network. It does not establish physical path MTU, individual packet
sizes, fragmentation behavior, jumbo UDP delivery or success of every application.
No raw-socket or UDP jumbo test is performed. Do not infer those claims even when
MTU 9000 or the maximum and the HTTPS body check both pass.

## Settings and vault continuity

Sign the continuity APK for the installed target, install it, and capture the
baseline before updating TUNA:

```text
adb install -r SecurityContinuity-signed.apk
adb shell am instrument -w -e phase before app.tuna.securityprobe.continuity/app.tuna.securityprobe.DataContinuityRunner
```

Expected result: `snapshot_saved`. The runner refuses to overwrite an existing
`noBackup/qa-security-continuity.json`. It stores only hashed entry IDs/values and
counts/UID. It reads `.preferences_pb` files directly, uses typed canonical values
and sorted sets, excludes `_tuna_secret_storage_version`, and decrypts marked
string/string-set entries using the existing `tuna.preferences.aes.v1` key and
`tuna.preferences/v1/{string|set}/{key}` AAD. It never creates a key or changes
settings. XML shared preferences are compared by file hash.

It also preserves the ciphertext hash, decrypted plaintext hash and server count
of `server-access-v1.enc`, using the existing `tuna-server-access-v1` key and
`TUNA/server-access/1` AAD. No preference names, values, vault plaintext or hashes
of values are emitted to instrumentation output. Changed entry IDs, if any, are
SHA-256 hashes of namespaced keys. Baseline data stays in the target's noBackup.

After the authorized target update and normal app startup/migration, sign the
runner for the current target signer if required and run:

```text
adb shell am instrument -w -e phase compare app.tuna.securityprobe.continuity/app.tuna.securityprobe.DataContinuityRunner
```

Expected result: `unchanged`, `changedEntries=0`, `uidPreserved=true`,
`vaultPreserved=true`, `appPreferencesEncrypted=true`. A deliberate dry-run against
the old unencrypted build may add `-e requireEncrypted false`; do not use that
override as migration acceptance. Run while settings are stable. Legitimate
intervening user changes also fail equality. No baseline cleanup occurs.

## Installed-code runtime acceptance

Use the current APK's R8 mapping to pass the mapped class names. The runner loads
the target classloader and locates members by parameter/field types. It fails
explicitly if R8 removed/inlined a required member; it does not replace installed
code with a host copy. Sign this separate APK for the installed target:

The release rules retain only `UpdateSecurity.INSTANCE` and the
`verifySignature(File,int,List)` entry point while the updater is disabled by
`PRIVATE_DISTRIBUTION`. Their names may still be obfuscated. This allows the same
signature boundary to run on Android before updater activation; it does not keep
other update helpers or create an exported component. Read both mapped class
names again after rebuilding because R8 identifiers can change.

```text
adb install -r SecurityAcceptance-signed.apk
adb shell am instrument -w -e bridgeClass '<mapped SystemDnsBridge>' -e updateClass '<mapped UpdateSecurity>' -e native true app.tuna.securityprobe.acceptance/app.tuna.securityprobe.AcceptanceRunner
```

The bridge checks send a framed `example.com` A query through the installed
SystemDnsBridge, validate ID/QR/truncation and timing, require malformed-length
closure, check a 150 ms idle deadline and verify that close removes the socket.
Closure reports the immediate observation separately and requires the endpoint
to be unavailable after another 100 ms; a successful final connect is a failure.
The updater check invokes the installed `verifySignature(File,int,List)` with the
installed APK and its current PackageManager certificates, exercising Android's
actual apksig runtime path. These positive checks do not replace the updater's
negative APK fixtures or prove Android network-change behavior.

Optional `-e negative true` additionally calls that installed method with the
ordinary probe APK and the current TUNA certificates, requiring signer rejection.
If package visibility hides the probe, supply `-e differentSignerApk '<probe base.apk
path from pm path app.tuna.securityprobe>'`. It also copies the installed target
APK into a temporary private file, flips one byte inside the first ZIP entry's
payload without altering ZIP headers, requires verification rejection, and deletes
that copy in `finally`. Expected fields are `differentSignerRejected=true`,
`modifiedApkRejected=true`, `modifiedApkRemoved=true`; no install intent is sent.

With `-e native true`, it launches the installed `libxray.so` using a temporary
app-private raw config. A private authenticated loopback SOCKS connection to
`example.com:443` triggers a DNS pool whose sole primary is closed local TCP port
1 and whose terminal reserve is Android system DNS. It requires the exact native
`TUNA_DNS_STATUS remote 0 emergency-system` observation and a bounded duration.
The synthetic wrapper removes Android JSONObject's optional slash escaping
before writing: JSON `\/` is equivalent to `/`, but the native YAML loader rejects
the former. This normalization applies only to the runner's generated fixture.
`nativeSocksConnectAccepted` reports the SOCKS acknowledgement, which Xray may send
before upstream DNS/connect. The runner consumes the full reply and keeps that
connection open while awaiting DNS telemetry; closing on acknowledgement would
cancel the query under test. It does not claim external TCP success. Diagnostic
output includes the last fixed phase, SOCKS numeric result codes, child exit state,
line count and a whitelist of fixed error categories. Only strictly validated DNS
status markers can appear verbatim. For this synthetic child only, up to three
startup lines (512 characters each) are reported after replacing its random
password, private paths, Android paths and bridge socket token. Remaining raw
child logs and synthetic credentials are discarded. The subprocess and config are cleaned up
in `finally`. No saved user profiles, VPN settings or network settings are changed.

Host compilation, signature verification and parser fixtures are local evidence.
Device acceptance requires actual owner-run installation/execution and recording
its results; an unsigned artifact cannot establish device success.

### In-memory configuration acceptance

`-e phase memory` runs an independent, read-only storage check and installed JNI
fixture. It reflects the retained `MemoryFileDescriptor` API, creates a random
synthetic canary in a memfd, opens that `/proc/self/fd` path twice using Android's
native `Os.open/read`, and requires exact bytes and EOF. Closing the descriptor
must make the path unavailable; a second close must succeed. No fixture file is
written, and no saved profile or setting is changed.

While that memfd is open, the runner scans regular files under the target's
files, cache and noBackup directories for only the exact synthetic bytes. It
does not decode or report stored contents or paths. It also counts known
generated runtime configuration filenames, including configs beneath
`noBackup/native-runtime`. Symlinks are skipped and counted. The scanner streams
64 KiB chunks and keeps the full regular-file scope, including large routing
databases. A limit of 20,000 files, 32 directory levels, 1 GiB or 120 seconds fails
acceptance rather than reporting an incomplete scan as clean. Partial counters,
elapsed time, configured limits and `memoryDiskScanStatus` are emitted even if
the scan fails. Status is `complete` or a fixed reason such as `byte_limit`,
`time_limit`, `file_limit`, `depth_limit`, `entry_missing`,
`directory_unreadable` or `filesystem_error`; it never contains a path or stored
content. Partial file counts include a file whose read was interrupted.
Existing continuity snapshots are neither changed nor removed.

Expected fields: `memoryNativeReopenMatches=true`, `memoryDescriptorClosed=true`,
`memoryRepeatedCloseSucceeded=true`, `memoryDiskScanComplete=true`,
`memoryDiskCanaryMatches=0`, `memoryForbiddenRuntimeFiles=0`, and `result=passed`.
The mode records VPN presence before and after; optional `-e requireVpn true`
requires a stable VPN network. Instrumentation launch can restart the target
process, so the default does not require VPN and a stopped-VPN result is not
evidence of storage behavior during a live tunnel. Run normal VPN connectivity
acceptance separately. This fixture does not start HEV or validate its full
asynchronous native lifetime; the application lifecycle tests cover ownership.

Host scanner regression tests compile the actual nested scanner using an
existing JDK, without building an APK or using a device:

```powershell
python android/security-probe/tests/test_memory_scan.py --java-home C:\path\to\jdk-21
```

The harness creates and removes only its own temporary synthetic files. A
257 MiB fixture proves the previous 256 MiB bound fails and the current bound
completes. Other cases cover chunk boundaries, partial counters, deadline/depth/
file limits, missing entries, forbidden filenames and overlapping matches.
This proves the scanner's bound handling, not the cause of a historical device
failure that did not report a scan reason. A fresh device acceptance result is
required; increasing limits alone is not a passing storage check.
