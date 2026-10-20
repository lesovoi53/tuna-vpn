"""Full native session through two same-IP endpoints; entirely synthetic loopback traffic.

The upstream SOCKS fixture consumes a synthetic domain without resolving/dialing it.
HTTPS trust/HTTP bounds are tested separately in the real JVM SOCKS/TLS fixture.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import random
import socket
import subprocess
import threading
import time


def recv_exact(connection, size):
    result = b""
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise EOFError("Synthetic stream was truncated")
        result += chunk
    return result


def terminate(process):
    if process is not None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    body = random.Random(1234).randbytes(32768)
    failures = []
    lines = []
    events = queue.Queue()
    accepted = threading.Event()
    done = threading.Event()
    upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    upstream.bind(("127.0.0.1", 0))
    upstream.listen(1)
    upstream.settimeout(30)
    silent = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    silent.bind(("127.0.0.1", 0))
    reserve = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    reserve.bind(("127.0.0.1", 0))
    server_port = reserve.getsockname()[1]
    reserve.close()
    reserve = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    reserve.bind(("127.0.0.1", 0))
    client_port = reserve.getsockname()[1]
    reserve.close()
    key = "0123456789abcdef0123456789abcdef"
    (directory / "fixture-key.txt").write_text(key, encoding="utf-8")
    (directory / "server.toml").write_text(
        f'UDP_HOST = "127.0.0.1"\nUDP_PORT = {server_port}\nDOMAIN = ["tunnel.example.invalid"]\n'
        'DATA_ENCRYPTION_METHOD = 5\nENCRYPTION_KEY_FILE = "fixture-key.txt"\n'
        'DNS_UPSTREAM_SERVERS = ["127.0.0.1:9"]\nLOG_LEVEL = "ERROR"\n'
        f'USE_EXTERNAL_SOCKS5 = true\nFORWARD_IP = "127.0.0.1"\nFORWARD_PORT = {upstream.getsockname()[1]}\n'
        'MAX_ALLOWED_CLIENT_RX_TX_WORKERS = 2\nMAX_ALLOWED_CLIENT_ARQ_WINDOW_SIZE = 32\n',
        encoding="utf-8")
    (directory / "client.toml").write_text(
        f'DOMAINS = ["tunnel.example.invalid"]\nDATA_ENCRYPTION_METHOD = 5\nENCRYPTION_KEY = "{key}"\n'
        f'PROTOCOL_TYPE = "SOCKS5"\nLISTEN_IP = "127.0.0.1"\nLISTEN_PORT = {client_port}\n'
        'SOCKS5_AUTH = true\nSOCKS5_USER = "fixture-user"\nSOCKS5_PASS = "fixture-pass"\n'
        'LOCAL_DNS_ENABLED = false\nMIN_UPLOAD_MTU = 64\nMAX_UPLOAD_MTU = 64\n'
        'MIN_DOWNLOAD_MTU = 512\nMAX_DOWNLOAD_MTU = 512\nMTU_TEST_TIMEOUT = 0.15\n'
        'MTU_TEST_RETRIES = 1\nMTU_TEST_PARALLELISM = 2\nLOG_LEVEL = "ERROR"\n'
        'SAVE_MTU_SERVERS_TO_FILE = false\nRX_TX_WORKERS = 8\nARQ_WINDOW_SIZE = 256\n'
        'UPLOAD_COMPRESSION_TYPE = 0\nDOWNLOAD_COMPRESSION_TYPE = 0\n',
        encoding="utf-8")
    # The first endpoint deliberately never responds. The historical IP-only
    # loader drops the second live port, which makes this acceptance fail.
    (directory / "resolvers.txt").write_text(
        f"127.0.0.1:{silent.getsockname()[1]}\n127.0.0.1:{server_port}\n", encoding="utf-8")

    def serve_upstream():
        try:
            with upstream.accept()[0] as connection:
                connection.settimeout(20)
                assert recv_exact(connection, 3) == b"\x05\x01\x00"
                connection.sendall(b"\x05\x00")
                assert recv_exact(connection, 4) == b"\x05\x01\x00\x03"
                hostname = recv_exact(connection, recv_exact(connection, 1)[0])
                assert hostname == b"fixture.example.invalid"
                assert recv_exact(connection, 2) == b"\x01\xbb"
                connection.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x01")
                assert recv_exact(connection, 4) == b"GET!"
                accepted.set()
                connection.sendall(body)
                done.wait(10)  # Let ARQ acknowledge the complete fixture before closing it.
        except Exception as error:
            failures.append(repr(error))

    worker = threading.Thread(target=serve_upstream, daemon=True)
    worker.start()
    client = server = None
    creation = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        with (directory / "server.log").open("w", encoding="utf-8") as server_log:
            server = subprocess.Popen([str(args.server.resolve()), "-config", str(directory / "server.toml"), "-nowait"],
                                      cwd=directory, stdout=server_log, stderr=subprocess.STDOUT, creationflags=creation)
            time.sleep(0.3)
            assert server.poll() is None, "Synthetic native server failed to start"
            client = subprocess.Popen(
                [str(args.client.resolve()), "-config", str(directory / "client.toml"),
                 "-resolvers", str(directory / "resolvers.txt"), "-diagnostic"],
                cwd=directory, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, creationflags=creation)

            def read_lines():
                for line in client.stdout:
                    lines.append(line)
                    events.put(line.strip())
                events.put("EOF")

            reader = threading.Thread(target=read_lines, daemon=True)
            reader.start()
            policy = None
            deadline = time.monotonic() + 25
            while True:
                line = events.get(timeout=max(0.1, deadline - time.monotonic()))
                if line.startswith("TUNA_MASTER_POLICY "):
                    policy = json.loads(line.partition(" ")[2])
                if line == "TUNA_MASTER_READY":
                    break
                assert line != "EOF" and not line.startswith("TUNA_MASTER_ERROR"), "Native diagnostic startup failed"
                assert time.monotonic() < deadline, "Native diagnostic startup deadline"
            assert policy and policy["resolverCount"] == 2, policy
            assert policy["rxTxWorkers"] == 2 and policy["window"] == 32, policy
            # Both absent-auth and incorrect password must be rejected by the real native listener.
            with socket.create_connection(("127.0.0.1", client_port), timeout=3) as connection:
                connection.sendall(b"\x05\x01\x00")
                assert recv_exact(connection, 2) == b"\x05\xff"
            with socket.create_connection(("127.0.0.1", client_port), timeout=3) as connection:
                connection.sendall(b"\x05\x01\x02")
                assert recv_exact(connection, 2) == b"\x05\x02"
                connection.sendall(b"\x01\x0cfixture-user\x05wrong")
                assert recv_exact(connection, 2) == b"\x01\x01"
            with socket.create_connection(("127.0.0.1", client_port), timeout=20) as connection:
                connection.sendall(b"\x05\x01\x02")
                assert recv_exact(connection, 2) == b"\x05\x02"
                connection.sendall(b"\x01\x0cfixture-user\x0cfixture-pass")
                assert recv_exact(connection, 2) == b"\x01\x00"
                target = b"fixture.example.invalid"
                connection.sendall(b"\x05\x01\x00\x03" + bytes([len(target)]) + target + b"\x01\xbb")
                assert recv_exact(connection, 4) == b"\x05\x00\x00\x01"
                recv_exact(connection, 6)
                connection.sendall(b"GET!")
                began = time.monotonic()
                received = recv_exact(connection, len(body))
                elapsed_ms = max(1, int((time.monotonic() - began) * 1000))
                assert received == body, "Native tunnel changed/truncated payload"
                assert accepted.is_set()
                done.set()
            report = {"case": "isolated-native-two-endpoint-session", "payloadBytes": len(received),
                      "elapsedMs": elapsed_ms, "sha256": hashlib.sha256(received).hexdigest(),
                      "effectiveParameters": policy, "authRejected": True,
                      "boundary": "loopback native session, not Android physical-Network or public resolver acceptance"}
            (directory / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report, indent=2))
    finally:
        done.set()
        terminate(client)
        terminate(server)
        upstream.close()
        silent.close()
        worker.join(timeout=3)
        (directory / "client.log").write_text("".join(lines), encoding="utf-8")
        if failures:
            raise AssertionError(f"Synthetic upstream failed: {failures}")


if __name__ == "__main__":
    main()
