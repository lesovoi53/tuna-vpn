# TUNA memory runtime launcher

`libtuna-runtime.so` is an arm64 PIE **executable**, not a JNI library. It reads
one bounded frame from stdin, creates sealed Linux memfds, substitutes opaque
file tokens, then calls `execv` directly. No configuration or frame is written to
disk. The resulting native process keeps the FDs open so parsers can seek and
reopen `/proc/self/fd/N` during reload. The launcher has no shell interpreter.

## Protocol TUNAMEM1

All integers are unsigned 32-bit big-endian. A blob is `length` followed by exactly
that many bytes. The sender writes and flushes one frame without closing stdin.
The launcher reads exactly the frame without read-ahead; subsequent bytes and the
open stream remain available to the native core (including qWDTT live controls).

1. Eight literal bytes `TUNAMEM1`.
2. `argc`, then `argc` argument blobs; argument zero is the executable path.
3. `filecount`, then `filecount` pairs of token blob and content blob.

Limits and validation:

- `argc`: 1вЂ“64; `filecount`: 0вЂ“32.
- The **entire input frame including headers** is at most 16 MiB. No individual
  blob may exceed 16 MiB; token blobs are at most 96 bytes. Aggregate replaced
  arguments and contents are independently capped at 16 MiB.
- Arguments and tokens must be valid UTF-8 without NUL. Contents must be valid
  UTF-8 and may contain NUL; all non-token bytes are preserved exactly.
- Tokens have the form `__TUNA_MEM_[A-Za-z0-9]+__`; names are case-sensitive,
  unique, nonempty and contain no other punctuation. Duplicate or invalid tokens
  fail before execution. Any occurrence of the full `__TUNA_MEM_` prefix in an
  argument/content must match a defined token or the frame is rejected.
- Argument zero is an absolute path to a basename ending in `.so`; repeated
  slashes, `.`/`..` traversal components and tokens in this path are rejected.
  The app must supply its own trusted `nativeLibraryDir` executable. This is
  not an allowlist of trusted native binaries; the launcher trusts its parent.

All memfds are allocated **before** replacement, supporting nested references
such as a MasterDNS config pointing at a second resolver file. Substitution is
literal and simultaneous across argv and file contents, plus the inherited
`TUNA_ROUTING_FILE` environment value if present; all other environment variables
are preserved. Unknown tokens in the designated routing variable fail closed.
Inserted FD paths are
not interpreted or expanded. Example sender fields:

```text
argv = ["/trusted/native/libmasterdns.so", "-config", "__TUNA_MEM_MAIN__"]
files = [("__TUNA_MEM_MAIN__", "resolvers: __TUNA_MEM_RESOLVERS__\n"),
         ("__TUNA_MEM_RESOLVERS__", "fixture-only\n")]
```

After writing, each FD is rewound and must accept `F_SEAL_WRITE`, `F_SEAL_GROW`,
`F_SEAL_SHRINK`, `F_SEAL_SEAL`; a later read verifies all required bits. Additional
kernel seals are accepted. FDs and the remaining stdin control stream are inherited
across exec, and core dumps are disabled. stdout/stderr are preserved. Original non-file arguments are unchanged and may still be visible
in process argv: callers must separately remove secrets from such arguments.

Every parsing, allocation, replacement, write, seek, seal or exec failure returns
125 and emits only a fixed stage name. It never prints filenames, argv, tokens,
configurations, errno text or frame bytes. Truncated frames and unavailable syscalls
are not silently substituted with disk storage. Signal termination closes descriptors;
on successful exec the native process owns them until its exit. A child that
explicitly closes inherited FDs cannot later reload these paths.

## Android compatibility boundary

Built with NDK 28.2.13676358 for arm64 API 26, with 16 KiB ELF segment alignment.
`memfd_create` is invoked by syscall, independent of the newer Bionic wrapper API.
The device kernel, app seccomp policy and SELinux policy must permit memfd, sealing
and reopening `/proc/self/fd`; an API level alone does not prove this. Unsupported
devices fail closed. There is deliberately no ashmem fallback: ashmem's mmap
interface does not provide the normal read/seek/reopen file semantics required by
the existing configuration parsers. The file is kernel-backed memory, not an
encrypted export or protection against privileged inspection of a running process.

## Build and tests

```powershell
./build.ps1 -AndroidSdk '<sdk-root>'
```

The script builds/inspects the PIE, copies it to
`app/src/main/jniLibs/arm64-v8a/libtuna-runtime.so` and the JNI companion
`libtuna-memory.so`, verifies both copies and updates
`provenance.json`. Root integration must independently add the component to its
native inventory/manifest and run installed-device acceptance.

Linux host tests use only `cc` and Python, and synthetic in-memory frame contents:

```text
python3 tests/test_launcher.py --build-dir /path/to/ignored/build/runtime-launcher-host
```

The test receiver reads, seeks and reopens each inherited file; verifies write and
truncate rejection, open stdin and later live-control bytes, nested token/env
substitution, exact NUL content and
literal non-shell arguments. Negative cases include malformed/truncated/oversized
frames, duplicate/unknown tokens, invalid UTF-8/NUL arguments, forbidden executable
paths, exec failure and descriptor exhaustion. All 32 memory files are also tested.
Results are saved as `host-test-results.json`. Host tests do not prove Android
syscall policy or compatibility with every packaged native parser.

## In-process JNI companion

`libtuna-memory.so` is a shared JNI library for the existing HEV JNI worker:

- `com.wireturn.app.kernel.MemoryFileDescriptor.nativeCreate(byte[]) -> int`
  creates a sealed seekable memfd (maximum 16 MiB), returns a descriptor >= 3,
  and throws `java.io.IOException` on failure. Arbitrary bytes are retained exactly.
- `nativeClose(int) -> void` closes one owned descriptor. Values below 3 are
  refused. The Kotlin owner must close exactly once and only after the native
  worker has stopped/joined; closing early invalidates future reopen attempts.

JNI FDs retain CLOEXEC because HEV runs in the same process; unrelated subprocesses
must not inherit them. Native Java method/class names must remain available after
R8. JNI calls do not create named files or read app settings. `nativeCreate` supports
both an outer-class static JNI method and an instance method; Kotlin `@JvmStatic`
companion bridging must resolve to the documented outer class. No `Companion`
class JNI symbols are provided.

The host suite optionally accepts `--jni-header /path/to/NDK/sysroot/usr/include/jni.h`.
It copies that existing header into the ignored build directory and tests the actual
JNI C implementation with a mock JNI function table: exact binary bytes, multi-chunk
writes, seek/reopen/seals, CLOEXEC, close, null/oversized inputs and injected JNI
read failure. This is not an Android VM/HEV lifecycle test. The updated local suite
completed 26 tests with 0 failures, including the JNI implementation check.
