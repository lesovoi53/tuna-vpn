"""Loopback regression: restored MAPDNS names must use client DNS before VLESS."""
import os
import socket
import struct
import subprocess
import threading
import time
from pathlib import Path

def read(sock, size):
    data = b""
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise EOFError()
        data += part
    return data

def run(enabled):
    counts = {"dns": 0, "domain": 0, "ip": 0}
    gate = socket.socket()
    gate.bind(("127.0.0.1", 0))
    gate.listen()
    gate.settimeout(0.2)
    stop = threading.Event()

    def handle(conn):
        with conn:
            conn.settimeout(5)
            header = read(conn, 18)
            read(conn, header[17])
            command, port, atyp = struct.unpack("!BHB", read(conn, 4))
            assert command == 1
            read(conn, {1: 4, 3: 16}.get(atyp, 0) if atyp != 2 else read(conn, 1)[0])
            conn.sendall(b"\x00\x00")
            if port == 53:
                counts["dns"] += 1
                q = read(conn, struct.unpack("!H", read(conn, 2))[0])
                qtype = struct.unpack("!H", q[-4:-2])[0]
                # Return only IPv4; AAAA is a successful empty response.
                answer = b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\xc0\x00\x02\x2a" if qtype == 1 else b""
                reply = q[:2] + struct.pack("!5H", 0x8180, 1, int(bool(answer)), 0, 0) + q[12:] + answer
                conn.sendall(struct.pack("!H", len(reply)) + reply)
            else:
                counts["domain" if atyp == 2 else "ip"] += 1
                conn.recv(4096)
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")

    def accept():
        while not stop.is_set():
            try:
                conn, _ = gate.accept()
            except socket.timeout:
                continue
            threading.Thread(target=handle, args=(conn,), daemon=True).start()

    thread = threading.Thread(target=accept, daemon=True)
    thread.start()
    reserve = socket.socket()
    reserve.bind(("127.0.0.1", 0))
    port = reserve.getsockname()[1]
    reserve.close()
    env = os.environ.copy()
    env["TUNA_RESOLVE_SOCKS_DESTINATION"] = "1" if enabled else "0"
    env.pop("TUNA_ROUTING_FILE", None)
    exe = Path(__file__).resolve().parents[1] / "build/native-cache/xray-network-check.exe"
    proc = subprocess.Popen([str(exe), "-link", f"vless://00000000-0000-4000-8000-000000000001@127.0.0.1:{gate.getsockname()[1]}?security=none&type=tcp", "-listen", f"127.0.0.1:{port}", "-dns", "tcp://192.0.2.53"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 8
        while True:
            try:
                client = socket.create_connection(("127.0.0.1", port), 1)
                break
            except OSError:
                if time.monotonic() > deadline:
                    raise RuntimeError("Local test core did not start")
                time.sleep(0.05)
        with client:
            client.settimeout(8)
            client.sendall(b"\x05\x01\x00")
            assert read(client, 2) == b"\x05\x00"
            name = b"mapdns-test.invalid"
            client.sendall(b"\x05\x01\x00\x03" + bytes([len(name)]) + name + struct.pack("!H", 80))
            assert read(client, 10)[1] == 0
            client.sendall(b"GET / HTTP/1.1\r\nHost: mapdns-test.invalid\r\nConnection: close\r\n\r\n")
            assert b"200 OK" in client.recv(4096)
    finally:
        proc.terminate()
        proc.wait(timeout=5)
        stop.set()
        thread.join(timeout=1)
        gate.close()
    print(f"forced_resolution={enabled}: {counts}")
    if enabled:
        assert counts["dns"] > 0 and counts["ip"] == 1 and counts["domain"] == 0
    else:
        assert counts["dns"] == 0 and counts["domain"] == 1

if __name__ == "__main__":
    run(False)
    run(True)
    print("PASS: VLESS payload resolution uses configured DNS, without server DNS delegation")
