# Application DNS overlay

`prepare.py` overlays the pinned Xray adapter and dependency in a private build
copy. It never edits the shared Go module cache. `build.ps1` runs adapter, DNS and
XHTTP tests before producing host/Android binaries and source/binary provenance.

Each adaptive pool has 1–8 primary resolvers and an optional terminal emergency
resolver. The emergency resolver has no health/probe entry and cannot become the
preferred primary. Primary attempts and the emergency share the caller's total
deadline. NXDOMAIN, valid NODATA and cancellation end the request; they do not
trigger an emergency query. Recovery checks reuse actual questions and are
bounded; no idle pool polling is started.

`TUNA_DNS_STATUS <remote|domestic> <index> <reason>` reports transitions without
question names or endpoint URLs. Primary indices start at 1. Emergency index 0
uses `emergency-system`, `emergency-resolver`, or their `-negative` variants.
Manual emergency requests retain their pool's route. System emergency always
uses the Android application's physical-network resolver bridge.

`TUNA_SYSTEM_DNS_SOCKET` contains the random abstract socket name `tuna-dns-*`.
Each accepted stream handles one DNS request:

1. Native writes a big-endian uint16 remaining timeout in milliseconds (1–5000).
2. Native writes a big-endian uint16 query length followed by the DNS wire query.
3. Android writes a big-endian uint16 response length followed by the DNS reply.

Lengths below 12 are errors. The client validates the response ID/question and
rejects truncated replies. Cancellation closes the stream. Android authenticates
the peer UID and chooses a NOT_VPN Network; native code never substitutes libc,
loopback DNS, or a public resolver when the bridge is unavailable.

`TUNA_BOOTSTRAP_DNS=system` sends Go resolver requests through the same bridge.
An explicit generated-policy bootstrap overrides the environment. Raw Xray
profiles bypass application policy parsing. Explicit literal TCP bootstrap stays
supported. DoH IP pins/static hosts take precedence, and bootstrap dialing keeps
the DoH hostname/SNI and normal certificate verification.

The sing-box overlay redirects only application bootstrap local transports when
`TUNA_BOOTSTRAP_DNS=system`. Its native emergency pool is not implemented; the
application must not silently compile that policy to the experimental sing-box
routing backend. Normal application routing uses the Xray overlay.

Host tests and freshly generated arm64 artifacts are not Android device, Private
DNS, physical network change, or remote server acceptance.
