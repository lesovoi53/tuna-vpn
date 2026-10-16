"""Validate Android-generated fixtures using the pinned host core builds."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
results = []
fixtures = sorted((ROOT / 'app/build/core-capabilities-fixtures').glob('*.json'))
assert len(fixtures) >= 11, 'Run Android unit tests first'
fixtures += [ROOT / 'app/build/xray-test-configs/hy2-hopping.json']
for path in fixtures:
    extended = path.name.startswith('extended-')
    executable = ROOT / ('build/native-cache/singbox-extended-check.exe' if extended else 'build/native-cache/xray-network-check.exe')
    args = ['check', '-c', str(path)] if extended else ['-config', str(path), '-check-config']
    run = subprocess.run([str(executable), *args], capture_output=True, timeout=20)
    results.append({'fixture': path.name, 'core': 'extended' if extended else 'xray', 'exitCode': run.returncode})
    print(path.name, run.returncode)
    if run.returncode:
        print(run.stderr.decode(errors='replace')[-3000:])
(ROOT / 'build/core-capabilities-validation.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
raise SystemExit(0 if all(r['exitCode'] == 0 for r in results) else 1)
