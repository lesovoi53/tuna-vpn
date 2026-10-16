"""Validate synthetic Cloudflare relay/candidate fixtures with the pinned desktop Xray adapter."""
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
binary = root / "build/native-cache/xray-network-check.exe"
fixtures = sorted(f for f in (root / "app/build/release-fixtures").glob("cf-*.json") if not f.name.startswith("cf-alpn-"))
assert len(fixtures) == 8, f"Run CloudflareProfileTest first; found {len(fixtures)} fixtures"
results = []
for fixture in fixtures:
    subprocess.run([str(binary), "-config", str(fixture), "-check-config"],
                   capture_output=True, timeout=15, check=True)
    response = subprocess.run([str(binary), "-config", str(fixture), "-dump-config"],
                              capture_output=True, timeout=15, check=True)
    config_line = next(line for line in response.stdout.splitlines() if line.startswith(b'{"dns"'))
    outbound = json.loads(config_line)["outbounds"][0]
    stream = outbound["streamSettings"]
    assert stream["security"] == "tls"
    assert stream["tlsSettings"]["serverName"] == "tls.example"
    assert stream["tlsSettings"]["fingerprint"] == "firefox"
    expected = "127.0.0.1" if "relay" in fixture.name else "104.16.1.1"
    assert outbound["settings"]["vnext"][0]["address"] == expected
    transport = stream["network"]
    if transport in ("xhttp", "ws", "httpupgrade"):
        settings = stream[transport + "Settings"]
        assert settings["host"] == "http.example"
        assert settings["path"] == "/a+b?id=2"
    results.append({"fixture": fixture.name, "valid": True})
alpn_fixtures = sorted((root / "app/build/release-fixtures").glob("cf-alpn-*.json"))
assert len(alpn_fixtures) == 5, "Run xhttpTlsMixedAlpnIsAcceptedWithoutChangingItsProtocols first"
for fixture in alpn_fixtures:
    import urllib.parse
    wrapper = json.loads(fixture.read_text())
    expected_alpn = urllib.parse.parse_qs(urllib.parse.urlsplit(wrapper["link"]).query)["alpn"][0].split(",")
    subprocess.run([str(binary), "-config", str(fixture), "-check-config"], capture_output=True, timeout=15, check=True)
    response = subprocess.run([str(binary), "-config", str(fixture), "-dump-config"], capture_output=True, timeout=15, check=True)
    config = json.loads(next(line for line in response.stdout.splitlines() if line.startswith(b'{"dns"')))
    stream = config["outbounds"][0]["streamSettings"]
    assert stream["network"] == "xhttp" and stream["security"] == "tls"
    assert stream["tlsSettings"]["alpn"] == expected_alpn
    results.append({"fixture": fixture.name, "valid": True, "alpnPreserved": True})
(root / "build/cf-native-results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
print(f"{len(results)} native configurations validated; no network connections performed.")
