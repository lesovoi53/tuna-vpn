"""Host-core adaptive DNS acceptance on loopback only; never touches Android or public DNS.

Uses the compiler fixture when present, otherwise the existing routing fixture with
equivalent eight-member pool declarations. Every resolver address is replaced by a
local TCP fixture; upstream VPN DNS requires an authenticated SOCKS connection.
"""
import argparse
import importlib.util
import json
import socket
import socketserver
import struct
import subprocess
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("dns_loopback", Path(__file__).with_name("test-network-dns-loopback.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def question_name(packet):
    pos, labels = 12, []
    while packet[pos]:
        size = packet[pos]
        labels.append(packet[pos + 1:pos + 1 + size].decode("ascii"))
        pos += size + 1
    return ".".join(labels), pos + 5


def resolver(answer, requests, failing=False, stall=False):
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(3)
            try:
                header = base.read_exact(self.request, 2)
                packet = base.read_exact(self.request, struct.unpack("!H", header)[0])
                name, end = question_name(packet)
                requests.append(name)
                if failing:
                    if stall:
                        self.request.recv(1)  # Hold the reply until cancellation or the fixture deadline.
                    return
                response = packet[:2] + struct.pack("!HHHHH", 0x8180, 1, 1, 0, 0) + packet[12:end]
                response += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 0, 4) + socket.inet_aton(answer)
                self.request.sendall(struct.pack("!H", len(response)) + response)
            except (OSError, EOFError):
                pass
    return base.Server(("127.0.0.1", 0), Handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--strategy", choices=["auto", "sequential", "parallel"], default="auto",
                        help="parallel is a negative control and must fail the no-fanout assertion")
    parser.add_argument("--primary-failure", choices=["close", "stall"], default="close")
    args = parser.parse_args()
    fixtures = ROOT / "app/build/network-compiler-fixtures"
    fixture = fixtures / "extended-dns-pools.json"
    generated_pool_fixture = fixture.is_file()
    if not generated_pool_fixture:
        fixture = fixtures / "extended-SELECTED_DIRECT.json"
    config = json.loads(fixture.read_text())
    histories = {route: [[] for _ in range(8)] for route in ("vpn", "direct")}
    resolvers = {route: [resolver("203.0.113.11" if route == "vpn" else "203.0.113.12", requests, failing=i == 0,
                                 stall=args.primary_failure == "stall")
                         for i, requests in enumerate(histories[route])] for route in histories}
    vpn_ports = {server.server_address[1] for server in resolvers["vpn"]}
    proxy_queries, proxy_connections, protocol_errors = [], [], []
    deny_proxy = threading.Event()
    upstream = next(item for item in config["outbounds"] if item["tag"] == "vpn")

    class Proxy(socketserver.BaseRequestHandler):
        def handle(self):
            stream = self.request
            stream.settimeout(3)
            try:
                assert base.read_exact(stream, 1) == b"\x05"
                assert 2 in base.read_exact(stream, base.read_exact(stream, 1)[0])
                stream.sendall(b"\x05\x02")
                assert base.read_exact(stream, 1) == b"\x01"
                user = base.read_exact(stream, base.read_exact(stream, 1)[0]).decode()
                password = base.read_exact(stream, base.read_exact(stream, 1)[0]).decode()
                assert (user, password) == (upstream["username"], upstream["password"]), "Authentication mismatch"
                stream.sendall(b"\x01\x00")
                assert base.read_exact(stream, 4) == b"\x05\x01\x00\x01"
                host = socket.inet_ntoa(base.read_exact(stream, 4))
                port = struct.unpack("!H", base.read_exact(stream, 2))[0]
                assert host == "127.0.0.1" and port in vpn_ports, "Cross-pool SOCKS destination"
                proxy_connections.append(port)
                if deny_proxy.is_set():
                    stream.sendall(b"\x05\x01\x00\x01" + b"\0" * 6)
                    return
                with socket.create_connection((host, port), timeout=2) as target:
                    stream.sendall(b"\x05\x00\x00\x01" + b"\0" * 6)
                    header = base.read_exact(stream, 2)
                    packet = base.read_exact(stream, struct.unpack("!H", header)[0])
                    proxy_queries.append((port, question_name(packet)[0]))
                    target.sendall(header + packet)
                    header = base.read_exact(target, 2)
                    stream.sendall(header + base.read_exact(target, struct.unpack("!H", header)[0]))
            except AssertionError as error:
                protocol_errors.append(str(error))
            except (OSError, EOFError):
                pass

    proxy = base.Server(("127.0.0.1", 0), Proxy)
    servers = [server for pool in resolvers.values() for server in pool] + [proxy]
    for server in servers:
        threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        listen_port = reserved.getsockname()[1]
    inbound = config["inbounds"][0]
    inbound.update(listen="127.0.0.1", listen_port=listen_port)
    upstream.update(server="127.0.0.1", server_port=proxy.server_address[1])
    retained = [item for item in config["dns"]["servers"] if item["tag"] == "static-hosts"]
    for route, pool in resolvers.items():
        tag = "dns-" + route
        members = []
        for i, server in enumerate(pool):
            member = f"{tag}-{i}"
            members.append(member)
            retained.append(dict(type="tcp", tag=member, detour=route,
                                 server="127.0.0.1", server_port=server.server_address[1]))
        retained.append(dict(type="fallback", tag=tag, servers=members, strategy=args.strategy, timeout="750ms"))
    config["dns"].update(servers=retained, disable_cache=True, strategy="ipv4_only")
    directory = ROOT / f"build/network-dns-pools-loopback-{args.strategy}-{args.primary_failure}"
    directory.mkdir(parents=True, exist_ok=True)
    result_path = directory / "result.json"
    result_path.unlink(missing_ok=True)
    config_path = directory / "config.json"
    config_path.write_text(json.dumps(config))
    process = None
    times = {}
    try:
        with (directory / "core.log").open("w") as log:
            process = subprocess.Popen([str(ROOT / "build/native-cache/singbox-extended-check.exe"), "run", "-c", str(config_path)],
                                       stdout=log, stderr=log, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            for _ in range(50):
                if process.poll() is not None:
                    raise RuntimeError("Core exited before readiness; inspect core.log")
                try:
                    with socket.create_connection(("127.0.0.1", listen_port), timeout=0.1):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise TimeoutError("Core did not listen")
            auth = inbound["users"][0]

            def resolve(name):
                started = time.monotonic()
                try:
                    return base.query(listen_port, auth["username"], auth["password"], name)
                finally:
                    times[name] = round(time.monotonic() - started, 3)

            assert resolve("first.vpn.example") == "203.0.113.11"
            time.sleep(0.1)
            assert histories["vpn"][0] and histories["vpn"][1], "Primary failure did not use backup"
            assert all(not requests for requests in histories["vpn"][2:]), "Fanned out to all eight VPN resolvers"
            assert all(not requests for requests in histories["direct"]), "VPN DNS leaked into DIRECT pool"
            assert resolve("first.direct.example") == "203.0.113.12"
            time.sleep(0.1)
            assert histories["direct"][0] and histories["direct"][1]
            assert all(not requests for requests in histories["direct"][2:]), "Fanned out to all eight DIRECT resolvers"
            if args.strategy == "auto":
                primary_counts = {route: len(histories[route][0]) for route in histories}
                assert resolve("winner.vpn.example") == "203.0.113.11"
                assert resolve("winner.direct.example") == "203.0.113.12"
                assert primary_counts == {route: len(histories[route][0]) for route in histories}, "Healthy winner was not reused"
            remote = [(resolvers["vpn"][i].server_address[1], name) for i, requests in enumerate(histories["vpn"]) for name in requests]
            assert sorted(remote) == sorted(proxy_queries), "VPN DNS bypassed authenticated SOCKS"
            assert all(name.endswith(".direct.example") for requests in histories["direct"] for name in requests)
            direct_before = sum(map(len, histories["direct"]))
            remote_before = sum(map(len, histories["vpn"]))
            deny_proxy.set()
            try:
                assert resolve("failure.vpn.example") is None
            except (OSError, EOFError):
                pass
            assert sum(map(len, histories["direct"])) == direct_before, "Failed VPN DNS fell back to DIRECT"
            assert sum(map(len, histories["vpn"])) == remote_before, "Failed VPN DNS bypassed proxy"
            assert resolve("still.direct.example") == "203.0.113.12"
            assert not protocol_errors, protocol_errors
            assert max(times.values()) < 3.5, f"Exceeded bounded query deadline: {times}"
            result = dict(status="PASS", strategy=args.strategy, primary_failure=args.primary_failure,
                          generated_pool_fixture=generated_pool_fixture,
                          primary_failover=True, winner_reuse=args.strategy == "auto", no_eight_way_fanout=True,
                          authenticated_socks_dns=True, direct_pool_separate=True, no_cross_pool_fallback=True,
                          query_seconds=times, android_tested=False, external_network_used=False)
            result_path.write_text(json.dumps(result, indent=2))
            print(json.dumps(result))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
