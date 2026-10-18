# Attribution and component licenses

TUNA VPN (formerly TunaTURN) is a derivative of WireTurn by spkprsnts, based on WireTurn v8.3, commit 4fe1cf6d14a0bc5a3a8f4f254f0dbb90ebb8d879. The original GPL-3.0 LICENSE remains unchanged. Upstream authorship is credited; the public TUNA repository starts with a new source snapshot rather than republishing private development history. WireTurn credits samosvalishe/turn-proxy-android as an earlier UI/logic foundation.

New Android modifications are provided under GPL-3.0. New code in external/vless-client is provided under MIT, following the upstream wrapper's README license declaration. That wrapper incorporates dependencies with their own licenses, including Xray-core (MPL-2.0), WireGuard (MIT), and gVisor (Apache-2.0). Upstream dependency notices remain applicable.

qWDTT (SpaceNeuroX and contributors) retain GPL-3.0. HEV Socks5 Tunnel retains heiher's MIT license. See each pinned upstream source and licenses bundled in app/src/main/assets/licenses. The root LICENSE does not relicense third-party components.

## Additional VPN engines

### qWDTT provenance

The qWDTT native client originates from SpaceNeuroX/proxy-turn-vk-android, based on WDTT by amurcanov and contributors (GPLv3), pinned at a296c57eaba69bb9479a24f9157856490890e47d by WireTurn v8.3. Android VK integration under app/src/main/java/com/wdtt/client is derived from that GPLv3 project and remains in this snapshot. The prebuilt qWDTT binary is extracted from the checksum-pinned WireTurn APK; its embedded VCS metadata reports the WireTurn root revision with modified=true. The pinned source association is documented, but bit-for-bit correspondence of that upstream prebuilt has not been independently established.

A local SOCKS authentication overlay is included in native/mieru and retains the upstream license.

sing-box-extended: https://github.com/shtorm-7/sing-box-extended, version v1.14.1-extended-2.7.2, commit 55faa763f986f4ca8a492d9b2719bc6330d2bef5. Based on sing-box by nekohasekai and contributors, with modifications by shtorm-7 and contributors. GPL-3.0-or-later; see the pinned source and app/src/main/assets/licenses/sing-box-extended-GPL.txt. Replaces the packaged sing-box-plus engine from TUNA 1.1.37; unused rollback source is not part of the public snapshot.

Mieru: https://github.com/enfein/mieru, commit 162c240b5366139a83dbd338e89907e8575ce0d8. Copyright the Mieru authors. GPL-3.0-or-later; see the pinned source and app/src/main/assets/licenses/Mieru-GPL.txt. The local launcher in external/mieru-adapter is GPL-3.0-or-later.

## Source availability

MasterDnsVPN: https://github.com/masterking32/MasterDnsVPN, tag v2026.06.13.234407-7de2476, commit 7de2476f1c33e69eec35360c52810dab43c5c986. Copyright Amin Mahmoudi (MasterkinG32), MIT; the original license is bundled as app/src/main/assets/licenses/MasterDnsVPN-MIT.txt. TUNA uses a separate native executable with a local CLI adapter and lifecycle log markers (native/masterdns); no WhiteDNS/NekoBox+ application code is copied. DNS tunneling protocol code is unchanged.

The source of TUNA VPN and its modified wrapper is published at https://github.com/lesovoi53/tuna-vpn. Pinned submodules identify upstream source; native-lock.json identifies the exact release APK used for HEV/qWDTT. Clone with --recurse-submodules. Preserve access to source, component notices and applicable license terms when redistributing binaries. Public documentation distinguishes source-built components from upstream prebuilts and does not claim a fully reproducible build.

## Optional RoscomVPN routing resources

The routing presets in TUNA are independently authored. No Happ/INCY JSON or Mihomo YAML is copied from hydraponique/roscomvpn-routing (no license declared at review).
Users may download GeoSite and GeoIP directly from hydraponique official releases using the resource manager; these databases are not bundled in this APK or repository. RoscomVPN GeoSite declares MIT (V2Ray, 2018–2019); RoscomVPN GeoIP aggregates third-party sources and had no repository license declaration at the original review. Downloading an optional dataset does not relicense it under TUNA's license; review its current upstream terms before redistribution.

RE2/J 1.8 (https://github.com/google/re2j), BSD-3-Clause; used for linear-time routing expression validation and preview. License: app/src/main/assets/licenses/RE2J-LICENSE.txt.

## WebDAV and NaiveProxy build components

WebDAV tunnel: https://github.com/spkprsnts/webdav-tunnel
Commit b1af4c05eb80fd29a6f676be894fa61cd12a3dec, MIT (WebDAV-MIT.txt).
TUNA overlays and build provenance: native/webdav/.

NaiveProxy outbound is supplied by the pinned sing-box-extended build with
with_naive_outbound. It uses SagerNet cronet-go (GPL-3.0-or-later), module
v0.0.0-20260912104727-0d28acc44093; see Cronet-Go-GPL.txt and
https://github.com/SagerNet/cronet-go.
Android arm64 Cronet 150.0.7871.63 is statically linked from library commit
c10c03c318db6d47bec8705c8290bad34bc32b64. Chromium copyright and BSD terms:
Chromium-BSD.txt. Chromium and its third-party source notices remain available
in the matching upstream Chromium source tree and the cronet-go build inputs.
The exact NaiveProxy source gitlink is 72a06c9fca0e2d228588c7f3074bf7efff3ff686
in SagerNet/naiveproxy. A broad collection of unchanged notices from that source
tree is bundled as Chromium-third-party-notices.zip; it is not a claim of a
minimal or independently verified static-link dependency inventory.
The Windows libcronet.dll is a host validation dependency only; it is not packaged in the APK.

## AWG, Snell v6 and TrustTunnel additions

TrustTunnel Chrome transport and ClientHello prefix adaptation from AlwaysBusyStack/NekoBoxForAndroid e4a4e055 (GPL-3.0-or-later; TrustTunnel-NekoBox-GPL.txt).
Snell QUIC Proxy adaptation from reF1nd/sing-box bccceeb3dabea6687212c1fff651a9bcb7733de1 and reF1nd/sing-snell 96ee7c6bc2e172915030a504af06942f117d4b51 (GPL-3.0-or-later; GPL-3.0.txt).
WireGuard/AWG uses github.com/shtorm-7/wireguard-go v0.0.6-extended-1.6.1 with the source-pinned TUNA handshake MAC fix. Build overlay inputs and hashes are recorded in native-lock.json and assets/native/singbox.json.

## Android APK verification

Android apksig 9.4.0, Copyright The Android Open Source Project.
Apache License 2.0; source https://android.googlesource.com/platform/tools/apksig/ .
Used for cryptographic APK signature and signing-lineage verification before installation.
