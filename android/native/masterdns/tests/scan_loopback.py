"""Synthetic local-only acceptance for the pinned host scanner and upstream server."""
from pathlib import Path
import argparse
from contextlib import ExitStack
import json
import socket
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scanner", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    scanner, server = args.scanner.resolve(), args.server.resolve()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reserve:
        reserve.bind(("127.0.0.1", 0))
        port = reserve.getsockname()[1]
    key = "0123456789abcdef0123456789abcdef"
    (directory / "fixture-key.txt").write_text(key, encoding="utf-8")
    server_config = directory / "server.toml"
    server_config.write_text(
        f'UDP_HOST = "127.0.0.1"\nUDP_PORT = {port}\nDOMAIN = ["tunnel.example.invalid"]\n'
        'DATA_ENCRYPTION_METHOD = 5\nENCRYPTION_KEY_FILE = "fixture-key.txt"\n'
        'DNS_UPSTREAM_SERVERS = ["127.0.0.1:9"]\nLOG_LEVEL = "ERROR"\n', encoding="utf-8"
    )

    def run_case(name, endpoint, selected_key=key):
        config = directory / "client.toml"
        config.write_text(
            f'DOMAINS = ["tunnel.example.invalid"]\nDATA_ENCRYPTION_METHOD = 5\nENCRYPTION_KEY = "{selected_key}"\n'
            'PROTOCOL_TYPE = "SOCKS5"\nLISTEN_IP = "127.0.0.1"\nLISTEN_PORT = 18000\n'
            'SOCKS5_AUTH = true\nSOCKS5_USER = "fixture"\nSOCKS5_PASS = "fixture"\n'
            'LOCAL_DNS_ENABLED = false\nMIN_UPLOAD_MTU = 64\nMAX_UPLOAD_MTU = 64\n'
            'MIN_DOWNLOAD_MTU = 96\nMAX_DOWNLOAD_MTU = 96\nMTU_TEST_TIMEOUT = 0.15\n'
            'MTU_TEST_RETRIES = 1\nMTU_TEST_PARALLELISM = 1\nLOG_LEVEL = "ERROR"\n', encoding="utf-8"
        )
        resolver = directory / "client_resolvers.txt"
        endpoints = [endpoint] if isinstance(endpoint, str) else endpoint
        resolver.write_text("\n".join(endpoints), encoding="utf-8")
        process = subprocess.run(
            [str(scanner), "-config", str(config), "-resolvers", str(resolver), "-scan"],
            cwd=directory, capture_output=True, text=True, timeout=30, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
        )
        (directory / f"{name}.log").write_text(process.stdout + process.stderr, encoding="utf-8")
        assert process.returncode == 0, f"{name}: native exit {process.returncode}"
        assert "TUNA_SCAN_DONE" in process.stdout
        results = [json.loads(line.partition(" ")[2]) for line in process.stdout.splitlines() if line.startswith("TUNA_RESOLVER ")]
        assert len(results) == len(endpoints), f"{name}: expected {len(endpoints)} results, got {len(results)}"
        tagged = [{"case": name, **result} for result in results]
        return tagged[0] if isinstance(endpoint, str) else tagged

    with (directory / "server.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            [str(server), "-config", str(server_config), "-nowait"], cwd=directory,
            stdout=log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
        )
        try:
            time.sleep(0.3)
            assert process.poll() is None, "synthetic server failed to start"
            positive = run_case("native-tunnel", f"127.0.0.1:{port}")
            assert positive["valid"] and positive["attempts"] == positive["successes"] == 5
            assert positive["upload"] == 64 and positive["download"] == 96
            (directory / "fixture-client.toml").write_text((directory / "client.toml").read_text(encoding="utf-8"), encoding="utf-8")
            # This is the Android relay shape: the same IP with distinct resolver ports.
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as silent:
                silent.bind(("127.0.0.1", 0))
                pair = run_case("same-ip-two-ports", [f"127.0.0.1:{port}", f"127.0.0.1:{silent.getsockname()[1]}"])
                assert sum(item["valid"] for item in pair) == 1
                assert next(item for item in pair if item["valid"])["successes"] == 5
                assert next(item for item in pair if not item["valid"])["successes"] == 0
            with ExitStack() as stack:
                full_pool = [f"127.0.0.1:{port}"]
                for _ in range(127):
                    silent = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
                    silent.bind(("127.0.0.1", 0))
                    full_pool.append(f"127.0.0.1:{silent.getsockname()[1]}")
                full_results = run_case("same-ip-all-128-ports", full_pool)
                assert {item["resolver"] for item in full_results} == set(full_pool)
                assert sum(item["valid"] for item in full_results) == 1
                pool_summary = {"case": "same-ip-all-128-ports", "results": len(full_results), "valid": 1, "invalid": 127}
                (directory / "128-endpoints.json").write_text(json.dumps(full_results, indent=2) + "\n", encoding="utf-8")
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as lossy:
                lossy.bind(("127.0.0.1", 0))
                lossy.settimeout(0.1)
                loss_stop = threading.Event()
                forwarded = []

                def forward_with_loss():
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as upstream:
                        upstream.connect(("127.0.0.1", port))
                        upstream.settimeout(1)
                        while not loss_stop.is_set():
                            try:
                                query, address = lossy.recvfrom(65535)
                            except TimeoutError:
                                continue
                            forwarded.append(len(query))
                            # Fixed MTU ranges send two discovery probes, followed by five samples.
                            if len(forwarded) in (4, 6):
                                continue
                            upstream.send(query)
                            answer = upstream.recv(65535)
                            time.sleep(0.02)
                            lossy.sendto(answer, address)

                loss_thread = threading.Thread(target=forward_with_loss)
                loss_thread.start()
                try:
                    loss = run_case("native-two-lost-samples", f"127.0.0.1:{lossy.getsockname()[1]}")
                    assert len(forwarded) == 7, f"Unexpected discovery/sample count {len(forwarded)}"
                    assert loss["valid"] and loss["attempts"] == 5 and loss["successes"] == 3
                    assert loss["latencyMs"] >= 20
                finally:
                    loss_stop.set()
                    loss_thread.join(timeout=2)
                    assert not loss_thread.is_alive()
            wrong_key = run_case("wrong-key", f"127.0.0.1:{port}", "fedcba9876543210fedcba9876543210")
            assert not wrong_key["valid"] and wrong_key["successes"] == 0
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as ordinary:
                ordinary.bind(("127.0.0.1", 0))
                ordinary.settimeout(0.1)
                stop = threading.Event()

                def respond():
                    while not stop.is_set():
                        try:
                            query, address = ordinary.recvfrom(65535)
                        except TimeoutError:
                            continue
                        answer = bytearray(query)
                        answer[2] |= 0x80
                        ordinary.sendto(answer, address)

                thread = threading.Thread(target=respond)
                thread.start()
                try:
                    dns_only = run_case("ordinary-dns-only", f"127.0.0.1:{ordinary.getsockname()[1]}")
                    assert not dns_only["valid"] and dns_only["successes"] == 0
                finally:
                    stop.set()
                    thread.join(timeout=1)
                    assert not thread.is_alive()
            report = [positive, *pair, pool_summary, loss, wrong_key, dns_only]
            (directory / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report, indent=2))
        finally:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)


if __name__ == "__main__":
    main()
