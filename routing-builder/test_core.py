import io
import struct
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import core


def binary_manifest(utf8=True, typed=False):
    strings = ["manifest", "package", "org.example.app"]
    blob = b""
    offsets = []
    for text in strings:
        offsets.append(len(blob))
        raw = text.encode("utf-8" if utf8 else "utf-16le")
        blob += (bytes([len(text), len(raw)]) + raw + b"\0") if utf8 else (struct.pack("<H", len(text)) + raw + b"\0\0")
    start = 28 + 4 * len(strings)
    pool = struct.pack("<HHIIIIII", 1, 28, start + len(blob), 3, 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack("<III", *offsets) + blob
    element = struct.pack("<HHIII", 0x102, 16, 56, 1, 0xFFFFFFFF)
    element += struct.pack("<IIHHHHHH", 0xFFFFFFFF, 0, 20, 20, 1, 0, 0, 0)
    element += struct.pack("<IIIHBBI", 0xFFFFFFFF, 1, 0xFFFFFFFF if typed else 2, 8, 0, 3, 2)
    return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(element)) + pool + element


def apk_bytes():
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as z:
        z.writestr("AndroidManifest.xml", binary_manifest())
    return output.getvalue()


class CoreTest(unittest.TestCase):
    def test_play(self):
        self.assertEqual(core.resolve("https://play.google.com/store/apps/details?id=com.android.chrome&hl=ru"), "com.android.chrome")

    def test_rustore(self):
        self.assertEqual(core.resolve("https://www.rustore.ru/catalog/app/org.telegram.messenger/"), "org.telegram.messenger")

    def test_bad_store(self):
        for url in ["https://play.google.com/store/apps/details?id=a.b&id=c.d", "https://rustore.ru/catalog/app/1234", "https://play.google.com/store/apps/details?id=bad", "file:///x.apk", "https://user:pass@example.org/app.apk"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                core.store_package(url)

    def test_no_host_spoof(self):
        self.assertIsNone(core.store_package("https://play.google.com.evil.example/store/apps/details?id=a.b"))

    def test_link(self):
        self.assertEqual(core.routing_link(["a.b", "a.b", "c.d"], "vpn"), "tuna://app-exclusions/v1?mode=vpn&packages=a.b,c.d")
        for packages, mode in [([], "vpn"), (["a.b"], "bad"), (["a.b&mode=vpn"], "bypass"), ([f"a.p{i}" for i in range(101)], "vpn")]:
            with self.assertRaises(ValueError):
                core.routing_link(packages, mode)

    def test_binary(self):
        for utf8 in (False, True):
            for typed in (False, True):
                self.assertEqual(core.manifest_package(binary_manifest(utf8, typed)), "org.example.app")

    def test_truncated(self):
        manifest = binary_manifest()
        for n in range(len(manifest)):
            with self.assertRaises(ValueError):
                core.manifest_package(manifest[:n])

    def test_text_manifest(self):
        self.assertEqual(core.manifest_package(b'<manifest package="org.example.app"/>'), "org.example.app")
        with self.assertRaises(ValueError):
            core.manifest_package(b'<!DOCTYPE a><manifest package="org.example.app"/>')

    def test_local_apk(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "app.apk"
            path.write_bytes(apk_bytes())
            self.assertEqual(core.resolve(str(path)), "org.example.app")


class DownloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = apk_bytes()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/app")
                    self.end_headers()
                    return
                if self.path == "/error":
                    self.send_error(403)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/html" if self.path == "/html" else "application/octet-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_direct_and_redirect(self):
        for endpoint in ("/app", "/redirect"):
            self.assertEqual(core.resolve(self.base + endpoint), "org.example.app")

    def test_http_errors(self):
        for endpoint in ("/html", "/error"):
            with self.assertRaises(ValueError):
                core.resolve(self.base + endpoint)

    def test_limit(self):
        with patch.object(core, "MAX_APK", 10), self.assertRaises(ValueError):
            core.resolve(self.base + "/app")

    def test_cancel(self):
        cancel = threading.Event()
        cancel.set()
        with self.assertRaisesRegex(ValueError, "отменена"):
            core.resolve(self.base + "/app", cancel)


if __name__ == "__main__":
    unittest.main()
