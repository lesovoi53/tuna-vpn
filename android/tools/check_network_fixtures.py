"""Native checks of generated common-router fixtures, without remote connections."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
fixtures = sorted((ROOT / 'app/build/network-compiler-fixtures').glob('*.json'))
assert len(fixtures) >= 10, 'Run Android unit tests first'
results = []
for path in fixtures:
    extended = path.name.startswith('extended-')
    core = ROOT / 'build/native-cache' / ('singbox-extended-check.exe' if extended else 'xray-network-check.exe')
    env = dict(os.environ)
    env.pop('TUNA_ROUTING_FILE', None)
    env.pop('TUNA_BOOTSTRAP_DNS', None)
    if extended:
        args = ['check', '-c', str(path)]
    elif path.name.startswith('xray-policy-'):
        env['TUNA_ROUTING_FILE'] = str(path)
        args = ['-listen', '127.0.0.1:19080', '-proxy-user', 'fixture', '-proxy-pass', 'fixture-password',
                '-local-socks5', 'fixture:fixture-password@127.0.0.1:19081', '-check-config']
    else:
        env['TUNA_BOOTSTRAP_DNS'] = 'tcp://[2001:db8::53]:5353'
        args = ['-config', str(path), '-check-config']
    run = subprocess.run([str(core), *args], env=env, capture_output=True, timeout=20)
    results.append(dict(fixture=path.name, exitCode=run.returncode,
                        coreSha256=hashlib.sha256(core.read_bytes()).hexdigest()))
    print(path.name, run.returncode)
    if run.returncode:
        print(run.stderr.decode(errors='replace')[-2000:])
(ROOT / 'build/network-native-validation.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
raise SystemExit(0 if all(result['exitCode'] == 0 for result in results) else 1)
