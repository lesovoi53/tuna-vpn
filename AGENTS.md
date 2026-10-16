# Working in this repository

This is a public source snapshot, not a backup of private subscriptions or devices.

- Keep signing keys, profiles, subscription tokens, device dumps and raw packet captures out of Git and issue reports. Use example.com / example.invalid, TEST-NET addresses and synthetic credentials in fixtures.
- Do not change package ID, release signing key, stored profile format, subscription ownership or user-selected routing silently.
- Preserve upstream attribution and license boundaries. Change native code through documented overlays; update provenance and rebuild after changing a native input.
- Do not reintroduce removed transports or Speedtest as a side effect of unrelated work.
- Read [architecture](docs/development/architecture.md) for service/network changes, [routing contract](docs/development/routing-contract.md) for import/export, and [build](docs/development/build.md) for artifacts.
- Before a release follow [testing](docs/development/testing.md) and [release](docs/development/release.md). Unit tests and a local APK build are not proof of device, energy, server or clean-runner acceptance.
- Short focused patches; never discard unrelated changes. Report actual commands/results and explicitly list checks not performed.

