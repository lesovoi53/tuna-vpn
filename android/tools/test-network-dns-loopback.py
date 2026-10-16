"""Exercise generated extended DNS routing on loopback, with no external servers.

Run unit tests first to generate app/build/network-compiler-fixtures.
This validates the host build, not Android TUN or public DNS leak behavior.
"""
import json
import socket
import socketserver
import struct
import subprocess
import threading
import time
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read_exact(stream, size):
    data = b""
    while len(data) < size:
        part = stream.recv(size - len(data))
        if not part:
            raise EOFError("connection closed")
        data += part
    return data


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


def dns_server(answer, requests, ttl=0):
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(4)
            try:
                while True:
                    size = struct.unpack("!H", read_exact(self.request, 2))[0]
                    query = read_exact(self.request, size)
                    pos, labels = 12, []
                    while query[pos]:
                        n = query[pos]
                        labels.append(query[pos + 1:pos + 1 + n].decode("ascii"))
                        pos += n + 1
                    end = pos + 5
                    requests.append(".".join(labels))
                    response = query[:2] + struct.pack("!HHHHH", 0x8180, 1, 1, 0, 0) + query[12:end]
                    response += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, ttl, 4) + socket.inet_aton(answer)
                    self.request.sendall(struct.pack("!H", len(response)) + response)
            except (OSError, EOFError):
                pass
    return Server(("127.0.0.1", 0), Handler)


def socks_connect(port, user, password, destination_port, command=1):
    stream = socket.create_connection(("127.0.0.1", port), timeout=3)
    try:
        stream.sendall(b"\x05\x01\x02")
        assert read_exact(stream, 2) == b"\x05\x02", "SOCKS auth not required"
        u, p = user.encode(), password.encode()
        stream.sendall(b"\x01" + bytes([len(u)]) + u + bytes([len(p)]) + p)
        assert read_exact(stream, 2) == b"\x01\x00", "SOCKS authentication failed"
        stream.sendall(bytes([5, command, 0, 1, 127, 0, 0, 1]) + struct.pack("!H", destination_port))
        reply = read_exact(stream, 4)
        assert reply[:2] == b"\x05\x00", "SOCKS CONNECT failed"
        if reply[3] == 1:
            host = socket.inet_ntoa(read_exact(stream, 4))
            bound_port = struct.unpack("!H", read_exact(stream, 2))[0]
        elif reply[3] == 4:
            host = socket.inet_ntop(socket.AF_INET6, read_exact(stream, 16))
            bound_port = struct.unpack("!H", read_exact(stream, 2))[0]
        else:
            raise AssertionError("Unexpected SOCKS bound address")
        return (stream, host, bound_port) if command == 3 else stream
    except BaseException:
        stream.close()
        raise


def query(port, user, password, name, udp=False, qtype=1):
    packet = struct.pack("!HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0)
    packet += b"".join(bytes([len(label)]) + label.encode() for label in name.split("."))
    packet += b"\0" + struct.pack("!HH", qtype, 1)
    if udp:
        control, host, bound_port = socks_connect(port, user, password, 0, command=3)
        with control, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as datagram:
            datagram.settimeout(3)
            datagram.sendto(b"\0\0\0\1\x7f\0\0\1\0\x35" + packet, (host, bound_port))
            response = datagram.recv(65535)
            assert response[:4] == b"\0\0\0\1"
            response = response[10:]
    else:
        with socks_connect(port, user, password, 53) as stream:
            stream.sendall(struct.pack("!H", len(packet)) + packet)
            size = struct.unpack("!H", read_exact(stream, 2))[0]
            response = read_exact(stream, size)
    if response[3] & 15 or struct.unpack("!H", response[6:8])[0] == 0:
        return None
    return socket.inet_ntoa(response[-4:])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=["extended", "xray"], default="extended")
    parser.add_argument("--dns-transport", choices=["tcp", "udp"], default="tcp")
    parser.add_argument("--cache", choices=["on", "off"], default="off")
    args = parser.parse_args()
    engine = args.engine
    fixture = ROOT / "app/build/network-compiler-fixtures/extended-SELECTED_DIRECT.json"
    config = json.loads(fixture.read_text())
    remote_requests, direct_requests, proxy_requests = [], [], []
    remote = dns_server("203.0.113.11", remote_requests, ttl=60)
    direct = dns_server("203.0.113.12", direct_requests, ttl=60)
    fail_upstream = threading.Event()
    upstream = config["outbounds"][0]

    class Socks(socketserver.BaseRequestHandler):
        def handle(self):
            stream = self.request
            stream.settimeout(4)
            try:
                assert read_exact(stream, 1) == b"\x05"
                methods = read_exact(stream, read_exact(stream, 1)[0])
                assert 2 in methods
                stream.sendall(b"\x05\x02")
                assert read_exact(stream, 1) == b"\x01"
                user = read_exact(stream, read_exact(stream, 1)[0]).decode()
                password = read_exact(stream, read_exact(stream, 1)[0]).decode()
                assert (user, password) == (upstream["username"], upstream["password"])
                stream.sendall(b"\x01\x00")
                assert read_exact(stream, 4) == b"\x05\x01\x00\x01"
                host = socket.inet_ntoa(read_exact(stream, 4))
                port = struct.unpack("!H", read_exact(stream, 2))[0]
                assert (host, port) == remote.server_address, "Unexpected outbound destination"
                proxy_requests.append(port)
                if fail_upstream.is_set():
                    stream.sendall(b"\x05\x01\x00\x01" + b"\0" * 6)
                    return
                with socket.create_connection((host, port), timeout=3) as target:
                    stream.sendall(b"\x05\x00\x00\x01" + b"\0" * 6)
                    # One DNS frame per connection, force close to avoid cached sessions
                    # hiding the deliberate upstream failure in the later assertion.
                    header = read_exact(stream, 2)
                    target.sendall(header + read_exact(stream, struct.unpack("!H", header)[0]))
                    header = read_exact(target, 2)
                    stream.sendall(header + read_exact(target, struct.unpack("!H", header)[0]))
            except (OSError, EOFError, AssertionError):
                return

    proxy = Server(("127.0.0.1", 0), Socks)
    servers = [remote, direct, proxy]
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        listen_port = reserved.getsockname()[1]
    inbound = config["inbounds"][0]
    inbound["listen"] = "127.0.0.1"
    inbound["listen_port"] = listen_port
    upstream["server"] = "127.0.0.1"
    upstream["server_port"] = proxy.server_address[1]
    for i, (tag, detour, server) in enumerate((("dns-vpn", "vpn", remote), ("dns-direct", "direct", direct))):
        config["dns"]["servers"][i] = dict(type="tcp", tag=tag, detour=detour,
                                           server="127.0.0.1", server_port=server.server_address[1])
    config["dns"]["disable_cache"] = args.cache == "off"
    config["dns"]["strategy"] = "ipv4_only"
    directory = ROOT / f"build/network-dns-loopback-{engine}-{args.dns_transport}-{args.cache}"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "result.json").unlink(missing_ok=True)
    path = directory / "config.json"
    path.write_text(json.dumps(config))
    environment = None
    command = [str(ROOT / "build/native-cache/singbox-extended-check.exe"), "run", "-c", str(path)]
    if engine == "xray":
        import os
        policy = json.loads((ROOT / "app/build/network-compiler-fixtures/xray-policy-SELECTED_DIRECT.json").read_text())
        policy['splitDns']['disableCache'] = args.cache == 'off'
        policy['splitDns']['queryStrategy'] = 'UseIPv4'
        for key, server in (("remote", remote), ("domestic", direct)):
            policy["splitDns"][key] = dict(transport="TCP", endpoint=f"127.0.0.1:{server.server_address[1]}")
        policy_path = directory / "policy.json"
        policy_path.write_text(json.dumps(policy))
        environment = dict(os.environ, TUNA_ROUTING_FILE=str(policy_path))
        auth = inbound["users"][0]
        command = [str(ROOT / "build/native-cache/xray-network-check.exe"),
                   "-listen", f"127.0.0.1:{listen_port}", "-proxy-user", auth["username"], "-proxy-pass", auth["password"],
                   "-local-socks5", f'{upstream["username"]}:{upstream["password"]}@127.0.0.1:{proxy.server_address[1]}']
    process = None
    try:
        with (directory / "core.log").open("w") as log:
            process = subprocess.Popen(command, env=environment, stdout=log, stderr=log,
                                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            for _ in range(50):
                if process.poll() is not None:
                    raise RuntimeError("Core exited before readiness; inspect loopback/core.log")
                try:
                    with socket.create_connection(("127.0.0.1", listen_port), timeout=0.1):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise TimeoutError("Core did not listen")
            auth = inbound["users"][0]
            def resolve(name):
                return query(listen_port, auth["username"], auth["password"], name, udp=args.dns_transport == "udp")
            assert resolve("vpn.example") == "203.0.113.11"
            assert remote_requests and set(remote_requests) == {"vpn.example"} and proxy_requests and not direct_requests, (remote_requests, proxy_requests, direct_requests)
            before_cache = len(remote_requests)
            assert resolve("vpn.example") == "203.0.113.11"
            if args.cache == "on":
                assert len(remote_requests) == before_cache, "Cached query reached upstream"
            else:
                assert len(remote_requests) > before_cache, "Disabled cache retained an answer"
            try:
                assert query(listen_port, auth["username"], auth["password"], "aaaa.vpn.example", udp=args.dns_transport == "udp", qtype=28) is None
            except (OSError, EOFError):
                pass
            assert "aaaa.vpn.example" not in remote_requests + direct_requests, "IPv4-only sent AAAA upstream"
            assert resolve("www.direct.example") == "203.0.113.12"
            assert direct_requests and set(direct_requests) == {"www.direct.example"}, direct_requests
            try:
                assert resolve("blocked.example") is None
            except (OSError, EOFError):
                pass
            # A core may retry the preceding successful query asynchronously.
            # Compare domain identities so that retries cannot masquerade as leaks.
            assert "blocked.example" not in remote_requests + direct_requests, "BLOCK queried a resolver"
            fail_upstream.set()
            try:
                assert resolve("failure.vpn.example") is None
            except (OSError, EOFError, AssertionError) as failure:
                if isinstance(failure, AssertionError) and str(failure) != "SOCKS CONNECT failed":
                    raise
            assert set(direct_requests) <= {"www.direct.example"}, f"VPN DNS fell back to DIRECT: {direct_requests}"
            assert resolve("still.direct.example") == "203.0.113.12"
            assert set(remote_requests) <= {"vpn.example"}, remote_requests
            assert set(direct_requests) <= {"www.direct.example", "still.direct.example"}, direct_requests
            result = dict(status="PASS", engine=engine, dns_transport=args.dns_transport, cache=args.cache, cache_behavior_verified=True, aaaa_filtered=True, vpn_dns_via_authenticated_socks=True, direct_dns_separate=True,
                          block_without_upstream_query=True, vpn_failure_no_direct_fallback=True,
                          android_tun_tested=False)
            (directory / "result.json").write_text(json.dumps(result, indent=2))
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
