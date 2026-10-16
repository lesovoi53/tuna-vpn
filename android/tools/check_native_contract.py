"""Run pinned Xray contract regressions against the existing build overlay."""
import argparse, json, subprocess
from pathlib import Path
p = argparse.ArgumentParser()
p.add_argument('--go', required=True)
a = p.parse_args()
r = Path(__file__).resolve().parents[1]
b = r / 'build/routing-overlay'
overlay = json.loads((b/'overlay.json').read_text())
overlay['Replace'][str(r/'external/vless-client/tuna_rc3_contract_test.go')] = str(r/'tests/synthetic-rc3/rc3_native_contract_test.go')
path = b/'release-contract-overlay.json'
path.write_text(json.dumps(overlay))
subprocess.run([a.go, 'test', '-count=1', '-timeout=120s', '-modfile', str(b/'native.mod'), '-overlay', str(path), '-run', 'TestAuditRc3', '.'], cwd=r/'external/vless-client', check=True)
