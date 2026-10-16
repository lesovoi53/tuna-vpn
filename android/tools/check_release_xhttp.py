"""Offline native validation of anonymized fixtures emitted by XhttpPacketUpImportTest."""
import json
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
binary = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / 'build/native-cache/xray-network-check.exe'
fixtures = root / 'app/build/release-fixtures'
results = []
for index in range(3):
    fixture = fixtures / f'xhttp-packet-{index}.json'
    subprocess.run([str(binary), '-config', str(fixture), '-check-config'],
                   check=True, capture_output=True, timeout=15)
    dump = subprocess.run([str(binary), '-config', str(fixture), '-dump-config'],
                          check=True, capture_output=True, timeout=15)
    config = json.loads(dump.stdout)
    transport = config['outbounds'][0]['streamSettings']['xhttpSettings']
    assert transport['path'] == f'/api/{index}/'
    assert transport['host'] == 'vpn.invalid' and transport['mode'] == 'packet-up'
    assert transport['uplinkHTTPMethod'] == ('POST' if index == 0 else 'GET')
    assert transport['uplinkDataPlacement'] == ('header' if index == 1 else 'body')
    assert transport['scMaxEachPostBytes'] == '524288-1048576'
    assert transport['sessionIDLength'] == '16-32'
    assert transport['headers']['User-Agent'] == 'Mozilla/5.0 Chrome/120.0.0.0'
    assert 'cMaxLifetimeMs' not in transport['xmux']
    results.append({'fixture': fixture.name, 'valid': True, 'effectiveFieldsVerified': True})
(root / 'build/rc17-native-results.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
print(f'{len(results)} native XHTTP configurations validated; no network connections performed.')
