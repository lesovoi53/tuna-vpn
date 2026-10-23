"""Publication guards: metadata, local doc links, no keys/private build artifacts.

This intentionally reports paths, never matching secret values. It is not a
comprehensive credential detector or security audit.
"""
import re
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
IGNORED = {'.git', 'build', 'dist', '.build-env', '.venv', '__pycache__', 'geo-cache', 'release-assets', '.gradle', '.kotlin'}
EXTERNAL = {'sing-box-extended', 'mieru', 'masterdnsvpn', 'proxy-turn-vk-android', 'hev-socks5-tunnel', 'webdav-tunnel'}

def candidates():
    # Walk only project-owned trees; do not traverse potentially huge submodules.
    import os
    for parent, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in IGNORED and not (Path(parent).name == 'external' and d in EXTERNAL)]
        for name in names:
            yield Path(parent) / name

def check():
    errors = []
    files = list(candidates())
    for path in files:
        rel = path.relative_to(ROOT).as_posix()
        if path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx', '.pcap', '.pcapng'} or path.name in {'.env', 'local.properties'}:
            errors.append(f'Private artifact: {rel}')
        if path.suffix.lower() not in {'.md', '.py', '.kt', '.java', '.json', '.yml', '.yaml', '.xml', '.ps1', '.txt', '.properties'}:
            continue
        data = path.read_text(encoding='utf-8-sig')
        # This exact invalid sentinel exercises encrypted-storage redaction.
        sentinel = '-----BEGIN' + ' OPENSSH PRIVATE KEY-----\\nsynthetic-only\\n-----END OPENSSH PRIVATE KEY-----'
        scanned = data.replace(sentinel, '') if rel == 'android/app/src/androidTest/java/com/wireturn/app/ServerVaultAudit.kt' else data
        if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', scanned):
            # Test fixtures must not contain actual PEM private keys in the public snapshot.
            errors.append(f'Private-key marker: {rel}')
        if re.search(r'gh[pousr]_[A-Za-z0-9]{30,}', data):
            errors.append(f'Potential GitHub token: {rel}')
        if path.suffix == '.md':
            for link in re.findall(r'(?<!!)\[[^\]]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', data):
                if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', link) or link.startswith('#'):
                    continue
                local = unquote(link.split('#', 1)[0])
                if local and not (path.parent / local).exists():
                    errors.append(f'Broken documentation link: {rel} -> {link}')
    props = (ROOT / 'android/gradle.properties').read_text()
    if 'project.versionName=1.65.2' not in props or 'project.versionCode=26102302' not in props:
        errors.append('Unexpected Android release metadata')
    if (ROOT / 'android/NOTICE.md').read_bytes() != (ROOT / 'android/app/src/main/assets/licenses/NOTICE.txt').read_bytes():
        errors.append('Packaged NOTICE differs')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'PASS: {len(files)} project files, release metadata, NOTICE and local Markdown links')

if __name__ == '__main__':
    check()
