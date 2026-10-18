# Global Android VPN MTU

The saved `vpn_tun_mtu` preference accepts every integer from 1280 through 65535;
missing or invalid stored values use 1280. It is independent of protocol MTUs.
Saving does not restart a connection. `HevVpnService` captures an immutable value
before establishing the TUN and uses it for both Android `setMtu` and HEV YAML.
Retargeting an existing descriptor retains that descriptor's original snapshot.

## Upper bound evidence

- [Android VPN JNI](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/jni/com_android_server_connectivity_Vpn.cpp)
  passes the integer MTU to `SIOCSIFMTU`; it does not impose a signed-16-bit cap.
- [Android common kernel android14-6.1 TUN](https://android.googlesource.com/kernel/common/+/refs/heads/android14-6.1/drivers/net/tun.c)
  defines `MAX_MTU = 65535`; TUN has no Ethernet header deduction.
- The repository HEV gitlink is `941c758101385d145c66210ac88991daaf27d4b6`.
  Its [hev-tunnel.h](https://github.com/heiher/hev-socks5-tunnel/blob/941c758101385d145c66210ac88991daaf27d4b6/src/hev-tunnel.h#L101)
  allocates the packet on the heap with `pbuf_alloc(PBUF_RAW, mtu, PBUF_RAM)`.
  Its pinned lwIP `2a11c14c7a32887af25a034e82ef18b0b12076ac` uses a `u16_t`
  packet length and a custom allocator with `size_t` allocation sizes. Thus the
  packet can be 65535 bytes while its aligned allocation exceeds 65535 bytes.
- This source pin alone does not establish binary equivalence: HEV is retained
  from the APK recorded in `android/native/prebuilt-provenance.json`.
  The actual arm64 `libhevsocks5.so` SHA-256 is
  `c2cb3b92964b0222b7ffd5ace3734b511c8ebf8b2cd496b9fd9599a73660af01`.
  On 2026-10-18, its disassembly corroborated the boundary: RAM `pbuf_alloc`
  at `0x4074c` masks the payload to 16 bits, rounds with `+3 & 0x1fffc`, adds
  the 24-byte pbuf header, and calls `mem_malloc` at `0x4076c`. `mem_malloc`
  at `0x3ead8` passes the untruncated 64-bit size to `hev_malloc`. The TUN
  read path calls `pbuf_alloc` at `0x220dc` and reads its unsigned-16-bit length
  at `0x220e8`. 65535 fits; 65536 wraps. Packet storage is heap allocated,
  so the configured 24576-byte task stack does not cap the MTU.

These are representation and interface limits, not a performance recommendation
or proof of every OEM kernel/network path. Device acceptance must separately
check the actual VPN LinkProperties MTU and traffic at selected values.
