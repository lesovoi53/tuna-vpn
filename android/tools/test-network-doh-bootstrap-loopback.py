"""Verify compiled DoH automatic bootstrap on the extended host core, loopback only."""
import datetime
import http.server
import importlib.util
import json
import select
import socket
import socketserver
import ssl
import struct
import subprocess
import threading
import time
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("pool_fixture", Path(__file__).with_name("test-network-dns-pools-loopback.py"))
pool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pool)
base = pool.base


def main():
    config = json.loads((ROOT / "app/build/network-compiler-fixtures/extended-doh-auto-bootstrap.json").read_text())
    directory = ROOT / "build/network-doh-bootstrap-loopback"
    directory.mkdir(parents=True, exist_ok=True)
    hostname = "andemikus.ru"
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, hostname)])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName(hostname)]), critical=False)
            .sign(key, hashes.SHA256()))
    cert_path, key_path = directory / "fixture-cert.pem", directory / "fixture-key.pem"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    bootstrap_queries = {route: [] for route in ("vpn", "direct")}
    doh_queries = {route: [] for route in bootstrap_queries}
    tls_names, proxy_connections, errors = [], [], []
    deny_proxy = threading.Event()
    bootstrap = {route: base.dns_server("127.0.0.1", requests) for route, requests in bootstrap_queries.items()}

    def https_server(route):
        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *_args):
                pass
            def do_POST(self):
                packet = self.rfile.read(int(self.headers["Content-Length"]))
                query, end = pool.question_name(packet)
                doh_queries[route].append(query)
                if self.path != "/dns-query" or self.headers["Host"].split(":")[0] != hostname:
                    errors.append("DoH path or HTTP Host changed")
                response = packet[:2] + struct.pack("!HHHHH", 0x8180, 1, 1, 0, 0) + packet[12:end]
                response += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 0, 4) + socket.inet_aton("203.0.113.11" if route == "vpn" else "203.0.113.12")
                self.send_response(200)
                self.send_header("Content-Type", "application/dns-message")
                self.send_header("Content-Length", str(len(response)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(response)
                self.close_connection = True
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert_path, key_path)
        context.set_servername_callback(lambda _socket, name, _context: tls_names.append(name))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        return server

    doh = {route: https_server(route) for route in bootstrap_queries}
    vpn_ports = {bootstrap["vpn"].server_address[1], doh["vpn"].server_address[1]}
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
                assert (user, password) == (upstream["username"], upstream["password"])
                stream.sendall(b"\x01\x00")
                assert base.read_exact(stream, 4) == b"\x05\x01\x00\x01", "Unresolved DoH hostname reached SOCKS"
                host = socket.inet_ntoa(base.read_exact(stream, 4))
                port = struct.unpack("!H", base.read_exact(stream, 2))[0]
                assert host == "127.0.0.1" and port in vpn_ports, "Cross-pool destination"
                proxy_connections.append(port)
                if deny_proxy.is_set():
                    stream.sendall(b"\x05\x01\x00\x01" + b"\0" * 6)
                    return
                with socket.create_connection((host, port), timeout=2) as target:
                    stream.sendall(b"\x05\x00\x00\x01" + b"\0" * 6)
                    while True:
                        ready, _, _ = select.select([stream, target], [], [], 3)
                        if not ready:
                            return
                        for source in ready:
                            data = source.recv(65536)
                            if not data:
                                return
                            (target if source is stream else stream).sendall(data)
            except AssertionError as error:
                errors.append(str(error))
            except (OSError, EOFError):
                pass

    proxy = base.Server(("127.0.0.1", 0), Proxy)
    servers = list(bootstrap.values()) + list(doh.values()) + [proxy]
    for server in servers:
        threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        listen_port = reserved.getsockname()[1]
    inbound = config["inbounds"][0]
    inbound.update(listen="127.0.0.1", listen_port=listen_port)
    upstream.update(server="127.0.0.1", server_port=proxy.server_address[1])
    members = {item["tag"]: item for item in config["dns"]["servers"]}
    for route in bootstrap_queries:
        members[f"dns-{route}-bootstrap"].update(server="127.0.0.1", server_port=bootstrap[route].server_address[1])
        member = members[f"dns-{route}-0"]
        assert member["server"] == hostname and member["domain_resolver"] == f"dns-{route}-bootstrap"
        member["server_port"] = doh[route].server_address[1]
        member["tls"]["certificate_path"] = str(cert_path)
        assert "insecure" not in member["tls"]
        members[f"dns-{route}"]["timeout"] = "1000ms"
    config["dns"].update(disable_cache=True, strategy="ipv4_only")
    config_path = directory / "config.json"
    config_path.write_text(json.dumps(config))
    process = None
    try:
        with (directory / "core.log").open("w") as log:
            process = subprocess.Popen([str(ROOT / "build/native-cache/singbox-extended-check.exe"), "run", "-c", str(config_path)], stdout=log, stderr=log, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            for _ in range(50):
                if process.poll() is not None:
                    raise RuntimeError("Core exited; inspect core.log")
                try:
                    with socket.create_connection(("127.0.0.1", listen_port), timeout=0.1):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise TimeoutError("Core not ready")
            auth = inbound["users"][0]
            resolve = lambda name: base.query(listen_port, auth["username"], auth["password"], name)
            assert resolve("first.vpn.example") == "203.0.113.11"
            assert not bootstrap_queries["direct"] and not doh_queries["direct"], "VPN query leaked direct"
            assert resolve("first.direct.example") == "203.0.113.12"
            assert set(proxy_connections) == vpn_ports, "VPN bootstrap or DoH bypassed SOCKS"
            assert all(names and set(names) == {hostname} for names in bootstrap_queries.values()), bootstrap_queries
            assert tls_names and set(tls_names) == {hostname}, "TLS SNI changed"
            before = {key: list(value) for key, value in doh_queries.items()}
            deny_proxy.set()
            try:
                assert resolve("failed.vpn.example") is None
            except (OSError, EOFError):
                pass
            assert doh_queries == before, "Failed VPN DNS fell back direct"
            assert resolve("still.direct.example") == "203.0.113.12"
            assert not errors, errors
            result = dict(status="PASS", endpoint_only_bootstrap=True, same_hostname_isolated_pools=True, authenticated_socks_bootstrap_and_doh=True, verified_tls_identity=True, no_cross_pool_fallback=True, external_network_used=False, android_tested=False)
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
