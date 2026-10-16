"""Check Kotlin import fixtures against the pinned native core, without listeners/network."""
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
binary = root / "build/native-cache/xray-network-check.exe"
fixtures = sorted((root / "app/build/vless-import-native-fixtures").glob("*.json"))
assert len(fixtures) == 9, "Run VlessNativeImportContractTest first"
results = []
for fixture in fixtures:
    result = subprocess.run([str(binary), "-config", str(fixture), "-check-config"],
                            capture_output=True, timeout=20)
    expected = fixture.name.startswith("pass-")
    actual = result.returncode == 0
    results.append({"case": fixture.stem, "accepted": actual, "expected": expected})
    assert actual == expected, f"Native validation mismatch: {fixture.name}"
print(json.dumps({"status": "PASS", "network_used": False, "results": results}))
