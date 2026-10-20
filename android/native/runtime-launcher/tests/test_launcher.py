"""Linux host integration tests: no Android device or real configuration involved."""
import argparse
import json
import os
from pathlib import Path
import resource
import shutil
import struct
import subprocess

HERE = Path(__file__).resolve().parent
MAX_BYTES = 16 * 1024 * 1024
TOKEN = b"__TUNA_MEM_PRIMARY__"
SECOND = b"__TUNA_MEM_SECONDARY__"


def u32(value):
    return struct.pack(">I", value)


def blob(value):
    return u32(len(value)) + value


def frame(arguments, files):
    return (b"TUNAMEM1" + u32(len(arguments)) + b"".join(map(blob, arguments))
            + u32(len(files)) + b"".join(blob(token) + blob(content) for token, content in files))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--jni-header", type=Path)
    args = parser.parse_args()
    directory = args.build_dir.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    launcher, receiver = directory / "libtuna-runtime.so", directory / "test-receiver.so"
    flags = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie"]
    for source, binary in [(HERE.parent / "launcher.c", launcher), (HERE / "receiver.c", receiver)]:
        subprocess.run(["cc", *flags, "-o", str(binary), str(source)], check=True)
    arguments = [os.fsencode(receiver), TOKEN, SECOND, b"literal;$(not-a-shell)", "UTF8-é".encode()]
    files = [(TOKEN, b"resolver=" + SECOND + b"\nsecret=fixture-only\n"), (SECOND, b"fixture\0bytes\n")]
    good = frame(arguments, files)
    environment = dict(os.environ, TUNA_ROUTING_FILE=SECOND.decode(), TUNA_TEST_UNCHANGED="ordinary-value")
    results = []

    def check(name, data, expected=False, limit_fds=False):
        def limits():
            if limit_fds:
                resource.setrlimit(resource.RLIMIT_NOFILE, (4, 4))
        process = subprocess.run([str(launcher)], input=data, capture_output=True,
                                 timeout=10, env=environment, preexec_fn=limits if limit_fds else None)
        if expected:
            assert process.returncode == 0 and process.stdout == b"PASS\n" and not process.stderr, (name, process.returncode, process.stderr)
        else:
            assert process.returncode == 125 and not process.stdout, (name, process.returncode)
            assert process.stderr.startswith(b"TUNA memory launcher failed: "), name
            assert b"fixture" not in process.stderr and b"__TUNA" not in process.stderr, name
        results.append({"name": name, "passed": True})

    check("sealed-seekable-reopen-nested-token-nul-content-literal-argv-stdin-control-env", good + b"PAUSE\n", True)
    check("bad-magic", b"BADMAGIC" + good[8:])
    check("truncated-header", good[:11])
    check("truncated-payload", good[:-1])
    check("zero-arguments", b"TUNAMEM1" + u32(0))
    check("too-many-arguments", b"TUNAMEM1" + u32(65))
    check("oversized-declaration", b"TUNAMEM1" + u32(1) + u32(MAX_BYTES + 1))
    check("total-frame-limit", frame([arguments[0]], [(TOKEN, b"x" * MAX_BYTES)]))
    check("too-many-files", b"TUNAMEM1" + u32(1) + blob(arguments[0]) + u32(33))
    check("duplicate-token", frame(arguments, files + [files[0]]))
    check("invalid-token", frame(arguments, [(b"__TUNA_MEM_BAD_NAME__", b"x")]))
    check("empty-token-label", frame(arguments, [(b"__TUNA_MEM___", b"x")]))
    check("unknown-argv-token", frame(arguments + [b"__TUNA_MEM_MISSING__"], files))
    check("unknown-content-token", frame(arguments, [(TOKEN, b"__TUNA_MEM_MISSING__"), files[1]]))
    check("nul-argument", frame(arguments + [b"a\0b"], files))
    check("nul-token", frame(arguments, [(TOKEN + b"\0", b"x")]))
    check("invalid-utf8-argument", frame(arguments + [b"\xc0\xaf"], files))
    check("invalid-utf8-content", frame(arguments, [(TOKEN, b"\xed\xa0\x80")]))
    check("relative-executable", frame([b"test.so"], []))
    check("traversal-executable", frame([b"/tmp/../test.so"], []))
    check("non-so-executable", frame([b"/bin/sh"], []))
    check("missing-executable", frame([os.fsencode(directory / "missing.so")], files))
    check("descriptor-allocation-failure", good, limit_fds=True)
    # Multiple maximum-count files must be allocated before any replacement occurs.
    check("all32-files", frame(arguments, files + [(f"__TUNA_MEM_EXTRA{i}__".encode(), b"") for i in range(30)]) + b"PAUSE\n", True)
    # The parent need not close stdin or queue a control before the native exec.
    child = subprocess.Popen([str(launcher)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, env=environment)
    try:
        child.stdin.write(good)
        child.stdin.flush()
        import time
        deadline = time.monotonic() + 5
        while Path(f"/proc/{child.pid}/exe").resolve() != receiver:
            assert child.poll() is None and time.monotonic() < deadline, "exec waited for stdin EOF"
            time.sleep(0.01)
        child.stdin.write(b"PAUSE\n")
        child.stdin.flush()
        child.wait(timeout=5)
        assert child.returncode == 0 and child.stdout.read() == b"PASS\n" and not child.stderr.read()
        results.append({"name": "exec-with-open-stdin-then-control", "passed": True})
    finally:
        if child.poll() is None:
            child.kill()
        child.stdin.close()
        child.wait()
    if args.jni_header:
        headers = directory / "jni-headers"
        headers.mkdir(exist_ok=True)
        shutil.copyfile(args.jni_header, headers / "jni.h")
        jni_test = directory / "jni-host-test"
        subprocess.run(["cc", *flags, "-I", str(headers), "-o", str(jni_test),
                        str(HERE / "jni_host_test.c"), str(HERE.parent / "memory_jni.c")], check=True)
        subprocess.run([str(jni_test)], check=True)
        results.append({"name": "jni-implementation-with-mock-env", "passed": True})
    summary = {"tests": len(results), "failures": 0, "results": results}
    (directory / "host-test-results.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"tests": len(results), "failures": 0}))


if __name__ == "__main__":
    main()
